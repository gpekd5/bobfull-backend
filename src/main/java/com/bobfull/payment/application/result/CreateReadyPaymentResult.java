package com.bobfull.payment.application.result;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

// 결제 서비스가 예약 도메인에 반환하는 READY 결제 생성 결과다.
public record CreateReadyPaymentResult(
        String paymentId,
        PaymentStatus paymentStatus,
        BigDecimal amount,
        Instant expiresAt
) {

    public static CreateReadyPaymentResult from(Payment payment) {
        return new CreateReadyPaymentResult(
                payment.getPaymentId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getExpiresAt()
        );
    }
}
