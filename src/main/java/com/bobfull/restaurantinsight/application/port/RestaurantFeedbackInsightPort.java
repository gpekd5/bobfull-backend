package com.bobfull.restaurantinsight.application.port;

import com.bobfull.restaurantinsight.application.result.RestaurantFeedbackAnalysisResult;

// 검증된 리뷰 원문을 외부 AI 분석 구현에 전달하는 Application 경계다.
public interface RestaurantFeedbackInsightPort {

    RestaurantFeedbackAnalysisResult analyze(String content);
}
