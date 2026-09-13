package com.bobfull.admin.dto;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record AdminPaymentListItemResponse(
        String paymentId,
        Long memberId,
        Long reservationId,
        BigDecimal amount,
        String currency,
        PaymentStatus paymentStatus,
        OffsetDateTime paidAt
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static AdminPaymentListItemResponse from(Payment payment) {
        return new AdminPaymentListItemResponse(
                payment.getPaymentId(), payment.getMemberId(), payment.getReservationId(),
                payment.getAmount(), payment.getCurrency(), payment.getStatus(),
                payment.getPaidAt() == null ? null : OffsetDateTime.ofInstant(payment.getPaidAt(), SEOUL));
    }
}
