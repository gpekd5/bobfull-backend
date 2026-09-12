package com.bobfull.reservation.application.service;

import com.bobfull.reservation.presentation.dto.OwnerReservationCancellationResponse;
import com.bobfull.reservation.presentation.dto.ReservationCancellationRequest;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import org.springframework.stereotype.Service;

/** OWNER의 식당 귀책 예약 전체 취소 접수와 환불 요청 시작만 담당한다(Issue #46). */
@Service
public class OwnerReservationCancellationService {
    private final ReservationCancellationTransactionService transactionService;
    private final ReservationCancellationRefundPort reservationCancellationRefundPort;

    public OwnerReservationCancellationService(
            ReservationCancellationTransactionService transactionService,
            ReservationCancellationRefundPort reservationCancellationRefundPort
    ) {
        this.transactionService = transactionService;
        this.reservationCancellationRefundPort = reservationCancellationRefundPort;
    }

    public OwnerReservationCancellationResponse cancel(
            Long ownerMemberId, Long reservationId, ReservationCancellationRequest request) {
        var acceptance = transactionService.acceptByOwner(ownerMemberId, reservationId, request.reason());
        reservationCancellationRefundPort.requestRefunds(acceptance.refundCommand());
        return new OwnerReservationCancellationResponse(acceptance.reservationId());
    }
}
