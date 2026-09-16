package com.bobfull.reservation.presentation.request;

import com.bobfull.payment.domain.entity.PaymentPurpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * type=CREATE는 targetId로 TimeSlot(sessionId)을, type=JOIN은 targetId로 기존 reservationId를 가리킨다.
 */
public record ReservationPrepareRequest(
        @NotNull PaymentPurpose type,
        @NotNull Long targetId,
        @NotNull @Min(1) Integer partySize
) {
}
