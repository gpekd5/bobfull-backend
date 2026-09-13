package com.bobfull.restaurant.timeslot.presentation.dto;

import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;

public record DiningSessionIdResponse(Long sessionId) {

    public static DiningSessionIdResponse from(TimeSlot timeSlot) {
        return new DiningSessionIdResponse(timeSlot.getId());
    }
}
