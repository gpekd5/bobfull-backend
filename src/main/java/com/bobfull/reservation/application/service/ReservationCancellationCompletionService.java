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

// 환불 완료 결과를 참여자와 예약 상태에 반영해 취소를 확정한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCancellationCompletionService {

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final ReservationCancellationTransactionService transactionService;
    private final BusinessMetricRecorder businessMetricRecorder;
    private final Optional<ReservationCompletionTestPort> completionTestPort;

    // MANDATORY는 외부 환불 완료와 예약 취소 상태가 서로 다른 트랜잭션에서 확정되는 것을 막는다.
    // Reservation을 먼저 잠그고 조건부 UPDATE로 동시 완료 요청 중 하나에만 처리권을 준다.
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
        // 롤백된 상태 변경이 완료 지표와 로그에 남지 않도록 커밋 뒤에 기록한다.
        AfterCommitExecutor.run(() -> {
            log.info(
                    "event=RESERVATION_CANCELLATION_COMPLETED reservationId={} participantId={} "
                            + "afterReservationStatus={} afterParticipantStatus=CANCELLED completedAt={}",
                    reservationId, reservationParticipantId, afterReservationStatus, completedAt);
            businessMetricRecorder.increment(BusinessMetricEvent.RESERVATION_CANCELLATION_COMPLETED);
        });
    }
}
