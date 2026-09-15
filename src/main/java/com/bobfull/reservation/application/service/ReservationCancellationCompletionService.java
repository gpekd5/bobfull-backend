package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.reservation.application.port.ReservationCompletionTestPort;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 완료 후 Participant와 Reservation 상태를 마무리하는 예약 도메인 전용 서비스다.
 * 취소 접수·환불 요청 책임은 갖지 않으며, 결제 완료 트랜잭션에 참여해 내부 상태를 함께 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCancellationCompletionService {

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final ReservationCancellationTransactionService transactionService;
    private final BusinessMetricRecorder businessMetricRecorder;
    private final Optional<ReservationCompletionTestPort> completionTestPort;

    /** Reservation을 먼저 잠그고 조건부 UPDATE로 참여자 완료 처리권을 하나만 허용한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Long reservationId, Long reservationParticipantId, Instant completedAt) {
        Reservation reservation = reservationRepository.findWithLockById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        completionTestPort.ifPresent(port -> port.beforeCompletion(reservationId));

        int updatedRows = reservationParticipantRepository.completeCancelIfRequested(
                reservationParticipantId, completedAt);
        if (updatedRows == 0) {
            return;
        }

        if (reservation.isCancelling()) {
            boolean hasRemainingCancellation = !reservationParticipantRepository
                    .findAllWithLockByReservationIdAndParticipationStatus(
                            reservationId, ParticipationStatus.CANCEL_REQUESTED)
                    .isEmpty();
            if (!hasRemainingCancellation) {
                reservation.cancel();
            }
            logCancellationCompletedAfterCommit(
                    reservationId, reservationParticipantId, reservation.getReservationStatus(), completedAt);
            return;
        }

        transactionService.recalculateAfterCompletion(reservation);
        logCancellationCompletedAfterCommit(
                reservationId, reservationParticipantId, reservation.getReservationStatus(), completedAt);
    }

    private void logCancellationCompletedAfterCommit(
            Long reservationId,
            Long reservationParticipantId,
            ReservationStatus afterReservationStatus,
            Instant completedAt
    ) {
        AfterCommitExecutor.run(() -> {
            log.info(
                    "event=RESERVATION_CANCELLATION_COMPLETED reservationId={} participantId={} "
                            + "afterReservationStatus={} afterParticipantStatus=CANCELLED completedAt={}",
                    reservationId, reservationParticipantId, afterReservationStatus, completedAt);
            businessMetricRecorder.increment(BusinessMetricEvent.RESERVATION_CANCELLATION_COMPLETED);
        });
    }
}
