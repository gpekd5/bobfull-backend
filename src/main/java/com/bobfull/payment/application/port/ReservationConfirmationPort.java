package com.bobfull.payment.application.port;

import com.bobfull.payment.domain.entity.Payment;

// 결제 완료와 함께 예약 생성 또는 참여 확정을 요청하는 경계다.
public interface ReservationConfirmationPort {
    ReservationConfirmationResult confirm(Payment payment);

    record ReservationConfirmationResult(Long reservationId, Long participationId) {
        public ReservationConfirmationResult {
            if (reservationId == null || participationId == null) {
                throw new IllegalArgumentException("예약과 참여자 식별자는 필수입니다.");
            }
        }
    }
}
