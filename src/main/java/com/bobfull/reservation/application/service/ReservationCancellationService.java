package com.bobfull.reservation.application.service;

import com.bobfull.reservation.presentation.request.ReservationCancellationRequest;
import com.bobfull.reservation.presentation.response.ReservationCancellationResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 사용자의 예약 취소를 접수하고 커밋된 결과에 대해 환불을 요청한다.
@Service
@RequiredArgsConstructor
public class ReservationCancellationService {
    private final ReservationCancellationTransactionService transactionService;
    private final ReservationCancellationRefundPort reservationCancellationRefundPort;

    // 취소 상태를 먼저 확정한 뒤 잠금 트랜잭션 밖에서 외부 환불을 시작한다.
    public ReservationCancellationResponse cancel(
            Long memberId, Long reservationId, ReservationCancellationRequest request) {
        var acceptance = transactionService.accept(memberId, reservationId, request);
        List<ReservationCancellationRefundPort.RefundRequestResult> results =
                reservationCancellationRefundPort.requestRefunds(acceptance.refundCommand());
        String refundStatus = results.stream()
                .filter(result -> result.reservationParticipantId().equals(acceptance.actingParticipantId()))
                .findFirst()
                .map(ReservationCancellationRefundPort.RefundRequestResult::refundStatus)
                .orElse(null);
        return new ReservationCancellationResponse(
                acceptance.reservationId(),
                acceptance.actingParticipantId(),
                ParticipationStatus.CANCEL_REQUESTED,
                acceptance.scope(),
                refundStatus);
    }
}
