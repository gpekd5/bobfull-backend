package com.bobfull.restaurant.timeslot.presentation.dto;

public record DiningSessionBulkResponse(
        Long tableId,
        Integer createdSessionCount
) {
}
