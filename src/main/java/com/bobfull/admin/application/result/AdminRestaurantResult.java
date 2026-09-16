package com.bobfull.admin.application.result;

import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import java.time.Instant;

/** ADMIN 식당 목록·상세 조회의 조회 결과 1건이다(Issue #49). */
public record AdminRestaurantResult(
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
        Instant createdAt,
        Instant deletedAt
) {
}
