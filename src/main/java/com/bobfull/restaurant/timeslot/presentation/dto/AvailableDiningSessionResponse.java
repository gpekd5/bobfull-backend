package com.bobfull.restaurant.timeslot.presentation.dto;

import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import java.time.OffsetDateTime;

public record AvailableDiningSessionResponse(
        Long sessionId,
        Long tableId,
        Integer capacity,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        Integer availableCapacity,
        /** 이 회차를 이미 점유한 활성 Reservation이 없으면 null(=새로 예약 생성 가능). */
        Long reservationId,
        Integer currentParticipantCount
) {
    public static AvailableDiningSessionResponse of(
            TimeSlot timeSlot,
            Integer capacity,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Integer availableCapacity,
            Long reservationId,
            Integer currentParticipantCount
    ) {
        return new AvailableDiningSessionResponse(
                timeSlot.getId(),
                timeSlot.getSharedTableId(),
                capacity,
                startAt,
                endAt,
                availableCapacity,
                reservationId,
                currentParticipantCount
        );
    }
}
