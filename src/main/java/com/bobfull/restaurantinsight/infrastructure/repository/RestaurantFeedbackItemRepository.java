package com.bobfull.restaurantinsight.infrastructure.repository;

import com.bobfull.restaurantinsight.domain.entity.RestaurantFeedbackItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantFeedbackItemRepository extends JpaRepository<RestaurantFeedbackItem, Long> {
}
