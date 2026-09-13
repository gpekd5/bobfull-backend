package com.bobfull.restaurant.restaurant.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RestaurantUpdateRequest(
        @NotBlank String name,
        @NotBlank String description,
        @NotBlank String keyword,
        @NotNull Integer depositPerPerson,
        String imageKey
) {
}
