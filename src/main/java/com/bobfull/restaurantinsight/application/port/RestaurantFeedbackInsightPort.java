package com.bobfull.restaurantinsight.application.port;

import com.bobfull.restaurantinsight.application.result.RestaurantFeedbackAnalysisResult;

public interface RestaurantFeedbackInsightPort {

    RestaurantFeedbackAnalysisResult analyze(String content);
}
