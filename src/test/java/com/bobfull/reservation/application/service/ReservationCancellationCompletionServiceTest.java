package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ReservationCancellationCompletionServiceTest {

    @Mock ReservationRepository reservationRepository;
    @Mock ReservationParticipantRepository participantRepository;
    @Mock ReservationCancellationTransactionService transactionService;
    @Mock Reservation reservation;
    @Mock BusinessMetricRecorder businessMetricRecorder;
    @Mock ReservationCompletionTestHook completionTestHook;

    private ReservationCancellationCompletionService service() {
        return service(Optional.empty());
    }

    private ReservationCancellationCompletionService service(Optional<ReservationCompletionTestHook> delayHook) {
        return new ReservationCancellationCompletionService(
                reservationRepository, participantRepository, transactionService, businessMetricRecorder, delayHook);
    }

    @Test
    void 완료는_Reservation_락_후_조건부_UPDATE로_한번만_처리한다() {
        Instant now = Instant.now();
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.of(reservation));
        when(participantRepository.completeCancelIfRequested(2L, now)).thenReturn(0);

        service().complete(1L, 2L, now);

        InOrder order = inOrder(reservationRepository, participantRepository);
        order.verify(reservationRepository).findWithLockById(1L);
        order.verify(participantRepository).completeCancelIfRequested(2L, now);
        verifyNoInteractions(transactionService);
    }

    @Test
    void 마지막_요청완료면_CANCELLING_Reservation을_CANCELLED로_확정한다() {
        Instant now = Instant.now();
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.of(reservation));
        when(participantRepository.completeCancelIfRequested(2L, now)).thenReturn(1);
        when(reservation.isCancelling()).thenReturn(true);
        when(participantRepository.findAllWithLockByReservationIdAndParticipationStatus(
                1L, ParticipationStatus.CANCEL_REQUESTED)).thenReturn(List.of());

        service().complete(1L, 2L, now);

        verify(reservation).cancel();
        verifyNoInteractions(transactionService);
    }

    @Test
    void 다른_취소요청이_남아있으면_CANCELLING을_유지한다() {
        Instant now = Instant.now();
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.of(reservation));
        when(participantRepository.completeCancelIfRequested(2L, now)).thenReturn(1);
        when(reservation.isCancelling()).thenReturn(true);
        when(participantRepository.findAllWithLockByReservationIdAndParticipationStatus(
                1L, ParticipationStatus.CANCEL_REQUESTED)).thenReturn(List.of(mock(ReservationParticipant.class)));

        service().complete(1L, 2L, now);

        verify(reservation, never()).cancel();
        verifyNoInteractions(transactionService);
    }

    @Test
    void 취소완료_로그는_afterCommit에서_남긴다() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.of(reservation));
        when(participantRepository.completeCancelIfRequested(2L, now)).thenReturn(1);
        when(reservation.isCancelling()).thenReturn(true);
        when(participantRepository.findAllWithLockByReservationIdAndParticipationStatus(
                1L, ParticipationStatus.CANCEL_REQUESTED)).thenReturn(List.of());
        when(reservation.getReservationStatus()).thenReturn(ReservationStatus.CANCELLED);
        Logger logger = (Logger) LoggerFactory.getLogger(ReservationCancellationCompletionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        TransactionSynchronizationManager.initSynchronization();

        try {
            service().complete(1L, 2L, now);
            assertThat(appender.list).isEmpty();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            logger.detachAppender(appender);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=RESERVATION_CANCELLATION_COMPLETED");
            assertThat(event.getFormattedMessage()).contains("reservationId=1");
            assertThat(event.getFormattedMessage()).contains("participantId=2");
            assertThat(event.getFormattedMessage()).contains("afterReservationStatus=CANCELLED");
            assertThat(event.getFormattedMessage()).contains("completedAt=2026-08-05T00:00:00Z");
        });
    }

    @Test
    void 개별참여_취소완료면_남은인원으로_Reservation을_재계산한다() {
        Instant now = Instant.now();
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.of(reservation));
        when(participantRepository.completeCancelIfRequested(2L, now)).thenReturn(1);
        when(reservation.isCancelling()).thenReturn(false);

        service().complete(1L, 2L, now);

        verify(transactionService).recalculateAfterCompletion(reservation);
    }

    @Test
    void 지연_Hook이_있으면_락_획득_직후_완료_처리_전에_호출한다() {
        Instant now = Instant.now();
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.of(reservation));
        when(participantRepository.completeCancelIfRequested(2L, now)).thenReturn(0);

        service(Optional.of(completionTestHook)).complete(1L, 2L, now);

        InOrder order = inOrder(reservationRepository, completionTestHook, participantRepository);
        order.verify(reservationRepository).findWithLockById(1L);
        order.verify(completionTestHook).beforeCompletion(1L);
        order.verify(participantRepository).completeCancelIfRequested(2L, now);
    }

    @Test
    void 지연_Hook이_없으면_아무_영향이_없다() {
        Instant now = Instant.now();
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.of(reservation));
        when(participantRepository.completeCancelIfRequested(2L, now)).thenReturn(0);

        service(Optional.empty()).complete(1L, 2L, now);

        verifyNoInteractions(completionTestHook);
    }

    @Test
    void 존재하지_않는_Reservation이면_예외를_던진다() {
        Instant now = Instant.now();
        when(reservationRepository.findWithLockById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().complete(1L, 2L, now))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ReservationErrorCode.RESERVATION_ID_NOT_FOUND);

        verifyNoInteractions(participantRepository, transactionService);
    }
}
