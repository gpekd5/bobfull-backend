package com.bobfull.restaurant.image.presentation.response;

public record RestaurantImageUploadUrlResponse(
        String uploadUrl,
        String tempImageKey,
        String finalImageKey
) {
}
