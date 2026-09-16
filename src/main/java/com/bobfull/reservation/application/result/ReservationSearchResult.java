package com.bobfull.reservation.application.result;

import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
import java.time.Instant;

public record ReservationSearchResult(
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        Long sessionId,
        Long tableId,
        Integer capacity,
        Instant startAt,
        Instant endAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        Long currentParticipantCount,
        Long temporaryHeldCount
) {
    public int availableCapacity() {
        return ReservationCapacityPolicy.availableCapacity(capacity, currentParticipantCount, temporaryHeldCount);
    }

    public int confirmationThreshold() {
        return ReservationCapacityPolicy.confirmationThreshold(capacity);
    }
}
