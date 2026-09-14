package com.bobfull.restaurantinsight.application.port;

import com.bobfull.restaurantinsight.application.dto.RestaurantFeedbackAnalysis;

public interface RestaurantFeedbackInsightPort {
    Result analyze(String content);
    record Result(RestaurantFeedbackAnalysis analysis, String provider, String modelName) { }
}
