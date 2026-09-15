package com.bobfull.reservation.presentation.response;

import com.bobfull.reservation.application.result.ReservationSearchResult;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.OffsetDateTime;

public record ReservationSearchResponse(
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        Long sessionId,
        Long tableId,
        Integer capacity,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        Integer currentParticipantCount,
        Integer availableCapacity,
        Integer confirmationThreshold
) {
    public static ReservationSearchResponse of(
            ReservationSearchResult result,
            OffsetDateTime startAt,
            OffsetDateTime endAt
    ) {
        return new ReservationSearchResponse(
                result.reservationId(),
                result.restaurantId(),
                result.restaurantName(),
                result.sessionId(),
                result.tableId(),
                result.capacity(),
                startAt,
                endAt,
                result.reservationStatus(),
                result.recruitmentStatus(),
                Math.toIntExact(result.currentParticipantCount()),
                result.availableCapacity(),
                result.confirmationThreshold()
        );
    }
}
