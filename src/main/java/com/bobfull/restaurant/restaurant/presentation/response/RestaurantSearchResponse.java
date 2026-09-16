package com.bobfull.restaurant.restaurant.presentation.response;

import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;

public record RestaurantSearchResponse(
        Long restaurantId,
        String name,
        String address,
        String category,
        String keyword,
        Integer depositPerPerson,
        String imageUrl
) {
    public static RestaurantSearchResponse from(Restaurant restaurant) {
        return from(restaurant, null);
    }

    public static RestaurantSearchResponse from(Restaurant restaurant, String imageUrl) {
        return new RestaurantSearchResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                restaurant.getCategory(),
                restaurant.getKeyword(),
                restaurant.getDepositPerPerson(),
                imageUrl
        );
    }
}
