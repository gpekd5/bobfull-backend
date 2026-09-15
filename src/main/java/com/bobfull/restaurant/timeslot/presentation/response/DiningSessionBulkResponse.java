package com.bobfull.restaurant.timeslot.presentation.response;

public record DiningSessionBulkResponse(
        Long tableId,
        Integer createdSessionCount
) {
}
