package com.bobfull.admin.infrastructure.repository.query;

import com.bobfull.admin.application.result.AdminRestaurantResult;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminRestaurantRepository {

    Page<AdminRestaurantResult> searchRestaurants(
            String keyword, RestaurantStatus status, Boolean deleted, Pageable pageable);

    Optional<AdminRestaurantResult> findRestaurantDetail(Long restaurantId);
}
