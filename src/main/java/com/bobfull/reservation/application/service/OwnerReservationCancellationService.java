package com.bobfull.reservation.application.service;

import com.bobfull.reservation.presentation.response.OwnerReservationCancellationResponse;
import com.bobfull.reservation.presentation.request.ReservationCancellationRequest;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 식당 사유의 예약 전체 취소를 접수하고 환불을 요청한다.
@Service
@RequiredArgsConstructor
public class OwnerReservationCancellationService {
    private final ReservationCancellationTransactionService transactionService;
    private final ReservationCancellationRefundPort reservationCancellationRefundPort;

    // 취소 상태를 먼저 확정한 뒤 잠금 트랜잭션 밖에서 전체 환불을 시작한다.
    public OwnerReservationCancellationResponse cancel(
            Long ownerMemberId, Long reservationId, ReservationCancellationRequest request) {
        var acceptance = transactionService.acceptByOwner(ownerMemberId, reservationId, request.reason());
        reservationCancellationRefundPort.requestRefunds(acceptance.refundCommand());
        return new OwnerReservationCancellationResponse(acceptance.reservationId());
    }
}
