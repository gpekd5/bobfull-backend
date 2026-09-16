package com.bobfull.payment.application.command;

import com.bobfull.payment.domain.entity.PaymentPurpose;
import java.math.BigDecimal;

// 예약 도메인이 검증·계산해 결제 서비스에 전달하는 READY 결제 생성값이다.
public record CreateReadyPaymentCommand(
        Long memberId,
        Long timeSlotId,
        Long reservationId,
        PaymentPurpose purpose,
        Integer partySize,
        BigDecimal amount
) {
}
