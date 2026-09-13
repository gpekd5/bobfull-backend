package com.bobfull.payment.presentation.dto;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record PaymentDetailResponse(String paymentId, Long reservationId, Long participationId, PaymentPurpose paymentPurpose,
                                    Integer partySize, PaymentStatus paymentStatus, BigDecimal amount, String currency,
                                    OffsetDateTime expiresAt, OffsetDateTime paidAt) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static PaymentDetailResponse from(Payment payment) {
        return new PaymentDetailResponse(payment.getPaymentId(), payment.getReservationId(), payment.getReservationParticipantId(),
                payment.getPurpose(), payment.getPartySize(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                OffsetDateTime.ofInstant(payment.getExpiresAt(), SEOUL),
                payment.getPaidAt() == null ? null : OffsetDateTime.ofInstant(payment.getPaidAt(), SEOUL));
    }
}
