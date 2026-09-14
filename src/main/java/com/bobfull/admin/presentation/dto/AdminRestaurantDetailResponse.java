package com.bobfull.admin.presentation.dto;

import com.bobfull.admin.application.model.AdminRestaurantResult;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import java.time.OffsetDateTime;

public record AdminRestaurantDetailResponse(
        Long restaurantId,
        Long ownerMemberId,
        String ownerName,
        String name,
        String address,
        String category,
        String description,
        String keyword,
        Integer depositPerPerson,
        RestaurantStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public static AdminRestaurantDetailResponse of(AdminRestaurantResult result, OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        return new AdminRestaurantDetailResponse(
                result.restaurantId(), result.ownerMemberId(), result.ownerName(),
                result.name(), result.address(), result.category(), result.description(), result.keyword(),
                result.depositPerPerson(), result.status(), createdAt, deletedAt);
    }
}
