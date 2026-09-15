package com.bobfull.restaurant.restaurant.presentation.request;

import java.time.LocalDate;
import java.time.LocalTime;

public record RestaurantSearchRequest(
        String keyword,
        String category,
        LocalDate date,
        LocalTime time
) {
}
