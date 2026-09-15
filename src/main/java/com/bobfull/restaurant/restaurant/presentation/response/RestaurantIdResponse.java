package com.bobfull.restaurant.restaurant.presentation.response;

import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;

public record RestaurantIdResponse(Long restaurantId) {

    public static RestaurantIdResponse from(Restaurant restaurant) {
        return new RestaurantIdResponse(restaurant.getId());
    }
}
