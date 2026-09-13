package com.bobfull.restaurant.restaurant.infrastructure.repository;

import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchRequest;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RestaurantSearchRepository {

    Page<Restaurant> search(RestaurantSearchRequest request, Pageable pageable);
}
