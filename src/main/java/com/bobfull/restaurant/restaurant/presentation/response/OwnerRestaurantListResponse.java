package com.bobfull.restaurant.restaurant.presentation.response;

import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;

public record OwnerRestaurantListResponse(
        Long restaurantId,
        String name,
        String address,
        String category,
        Integer depositPerPerson,
        RestaurantStatus status,
        String imageUrl
) {
    public static OwnerRestaurantListResponse from(Restaurant restaurant) {
        return from(restaurant, null);
    }

    public static OwnerRestaurantListResponse from(Restaurant restaurant, String imageUrl) {
        return new OwnerRestaurantListResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                restaurant.getCategory(),
                restaurant.getDepositPerPerson(),
                restaurant.getStatus(),
                imageUrl
        );
    }
}
