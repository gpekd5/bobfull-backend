package com.bobfull.reservation.application.service;

import com.bobfull.reservation.presentation.dto.ReservationCancellationRequest;
import com.bobfull.reservation.presentation.dto.ReservationCancellationResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import java.util.List;
import org.springframework.stereotype.Service;

/** 사용자의 예약 취소 접수와 환불 요청 시작만 담당한다. */
@Service
public class ReservationCancellationService {
    private final ReservationCancellationTransactionService transactionService;
    private final ReservationCancellationRefundPort reservationCancellationRefundPort;

    public ReservationCancellationService(
            ReservationCancellationTransactionService transactionService,
            ReservationCancellationRefundPort reservationCancellationRefundPort
    ) {
        this.transactionService = transactionService;
        this.reservationCancellationRefundPort = reservationCancellationRefundPort;
    }

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
