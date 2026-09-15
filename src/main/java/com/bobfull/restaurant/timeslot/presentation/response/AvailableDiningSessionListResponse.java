package com.bobfull.restaurant.timeslot.presentation.response;

import java.util.List;

public record AvailableDiningSessionListResponse(
        Long restaurantId,
        List<AvailableDiningSessionResponse> content
) {
}
