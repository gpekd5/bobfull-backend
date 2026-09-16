package com.bobfull.restaurantinsight.application.model;

import com.bobfull.restaurantinsight.domain.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackCategory;
import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackSentiment;
import java.util.List;

// AI Provider의 구조화 응답을 전달하며 relevant=false인 항목은 저장 대상에서 제외된다.
public record RestaurantFeedbackAnalysis(
        boolean relevant,
        List<Item> items
) {

    public record Item(
            FeedbackCategory category,
            FeedbackAspectType aspectType,
            String normalizedAspect,
            FeedbackOpinionType opinionType,
            FeedbackSentiment sentiment
    ) {
    }
}
