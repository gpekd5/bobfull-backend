package com.bobfull.payment.infrastructure.reservation;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.application.port.ReservationConfirmationPort;
import com.bobfull.reservation.application.service.ReservationConfirmationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 결제 완료를 예약 도메인의 생성·참여 확정 흐름으로 연결한다.
@Component
@RequiredArgsConstructor
public class ReservationConfirmationAdapter implements ReservationConfirmationPort {

    private final ReservationConfirmationService service;

    @Override
    public ReservationConfirmationResult confirm(Payment payment) {
        ReservationConfirmationService.ReservationConfirmationResult result = service.confirm(
                payment.getPurpose(),
                payment.getTimeSlotId(),
                payment.getReservationId(),
                payment.getMemberId(),
                payment.getPartySize()
        );
        return new ReservationConfirmationResult(result.reservationId(), result.reservationParticipantId());
    }
}
