package com.bobfull.restaurant.restaurant.presentation.dto;

import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;

/**
 * 사용자용 식당 상세 조회 응답이다. OWNER 소유자 정보나 status는 포함하지 않는다.
 */
public record RestaurantDetailResponse(
        Long restaurantId,
        String name,
        String address,
        String category,
        String description,
        String keyword,
        Integer depositPerPerson,
        String imageUrl
) {
    public static RestaurantDetailResponse from(Restaurant restaurant) {
        return from(restaurant, null);
    }

    public static RestaurantDetailResponse from(Restaurant restaurant, String imageUrl) {
        return new RestaurantDetailResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                restaurant.getCategory(),
                restaurant.getDescription(),
                restaurant.getKeyword(),
                restaurant.getDepositPerPerson(),
                imageUrl
        );
    }
}
