package com.bobfull.restaurantinsight.presentation.dto;

import java.time.Instant;
import java.util.List;

public record RestaurantFeedbackInsightListResponse(Long restaurantId, Instant from, Instant to, List<RestaurantFeedbackInsightResponse> insights) { }
