package com.bobfull.reservation.presentation.response;

import com.bobfull.payment.application.dto.CreateReadyPaymentResult;
import com.bobfull.payment.domain.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ReservationPrepareResponse(
        String paymentId,
        PaymentStatus paymentStatus,
        BigDecimal amount,
        OffsetDateTime expiresAt
) {
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    public static ReservationPrepareResponse from(CreateReadyPaymentResult result) {
        return new ReservationPrepareResponse(
                result.paymentId(),
                result.paymentStatus(),
                result.amount(),
                result.expiresAt().atZone(SEOUL_ZONE).toOffsetDateTime()
        );
    }
}
