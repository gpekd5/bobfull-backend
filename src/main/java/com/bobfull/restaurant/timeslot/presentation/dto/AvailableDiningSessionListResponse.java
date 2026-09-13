package com.bobfull.restaurant.timeslot.presentation.dto;

import java.util.List;

public record AvailableDiningSessionListResponse(
        Long restaurantId,
        List<AvailableDiningSessionResponse> content
) {
}
