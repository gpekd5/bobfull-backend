package com.bobfull.restaurant.image.presentation.dto;

public record RestaurantImageUploadUrlResponse(
        String uploadUrl,
        String tempImageKey,
        String finalImageKey
) {
}
