package com.bobfull.restaurant.timeslot.presentation.response;

import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import java.time.OffsetDateTime;

public record DiningSessionResponse(
        Long sessionId,
        Long tableId,
        Integer capacity,
        OffsetDateTime startAt,
        OffsetDateTime endAt
) {
    public static DiningSessionResponse of(
            TimeSlot timeSlot,
            Integer capacity,
            OffsetDateTime startAt,
            OffsetDateTime endAt
    ) {
        return new DiningSessionResponse(
                timeSlot.getId(),
                timeSlot.getSharedTableId(),
                capacity,
                startAt,
                endAt
        );
    }
}
