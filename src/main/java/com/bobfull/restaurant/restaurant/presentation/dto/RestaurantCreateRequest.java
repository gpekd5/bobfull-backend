package com.bobfull.restaurant.restaurant.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RestaurantCreateRequest(
        @NotBlank String name,
        @NotBlank String address,
        @NotBlank String category,
        @NotBlank String description,
        @NotBlank String keyword,
        @NotNull Integer depositPerPerson,
        String imageKey
) {
}
