package com.bobfull.restaurantinsight.application.result;

import com.bobfull.restaurantinsight.application.model.RestaurantFeedbackAnalysis;

public record RestaurantFeedbackAnalysisResult(
        RestaurantFeedbackAnalysis analysis,
        String provider,
        String modelName
) {
}
