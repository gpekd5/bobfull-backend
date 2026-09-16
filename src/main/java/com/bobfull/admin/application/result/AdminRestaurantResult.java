package com.bobfull.admin.application.result;

import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import java.time.Instant;

// 관리자 식당 목록과 상세 조회에 사용하는 결과다.
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
