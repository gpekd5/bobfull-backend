package com.bobfull.admin.presentation.dto;

import com.bobfull.admin.application.model.AdminRestaurantResult;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import java.time.OffsetDateTime;

public record AdminRestaurantListItemResponse(
        Long restaurantId,
        Long ownerMemberId,
        String ownerName,
        String name,
        String category,
        RestaurantStatus status,
        OffsetDateTime createdAt
) {
    public static AdminRestaurantListItemResponse of(AdminRestaurantResult result, OffsetDateTime createdAt) {
        return new AdminRestaurantListItemResponse(
                result.restaurantId(), result.ownerMemberId(), result.ownerName(),
                result.name(), result.category(), result.status(), createdAt);
    }
}
