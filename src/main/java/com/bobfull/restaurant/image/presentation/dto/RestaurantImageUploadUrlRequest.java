package com.bobfull.restaurant.image.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RestaurantImageUploadUrlRequest(
        @NotBlank String extension,
        @NotBlank String contentType,
        @NotNull @Positive Long fileSize
) {
}
