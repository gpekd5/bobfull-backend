package com.bobfull.payment.application.dto;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payment 서비스가 예약 도메인에 반환하는 READY 생성 결과다.
 * HTTP 응답 DTO가 아니라 내부 출력 계약이며, Controller는 별도 Response DTO로 변환한다.
 */
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
