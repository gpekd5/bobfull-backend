package com.bobfull.payment.presentation.response;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record PaymentListResponse(
        String paymentId,
        Long reservationId,
        Long participationId,
        PaymentPurpose paymentPurpose,
        Integer partySize,
        BigDecimal amount,
        String currency,
        PaymentStatus paymentStatus,
        OffsetDateTime paidAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static PaymentListResponse from(Payment payment) {
        return new PaymentListResponse(
                payment.getPaymentId(),
                payment.getReservationId(),
                payment.getReservationParticipantId(),
                payment.getPurpose(),
                payment.getPartySize(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getPaidAt() == null ? null : OffsetDateTime.ofInstant(payment.getPaidAt(), SEOUL)
        );
    }
}
