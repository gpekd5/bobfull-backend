package com.bobfull.reservation.presentation.response;

import com.bobfull.reservation.domain.CancellationScope;
import com.bobfull.reservation.domain.entity.ParticipationStatus;

public record ReservationCancellationResponse(
        Long reservationId,
        Long participationId,
        ParticipationStatus participationStatus,
        CancellationScope cancellationScope,
        String refundStatus
) {
}
