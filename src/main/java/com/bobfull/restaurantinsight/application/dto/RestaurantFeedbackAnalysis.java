package com.bobfull.restaurantinsight.application.dto;

import com.bobfull.restaurantinsight.domain.entity.FeedbackCategory;
import com.bobfull.restaurantinsight.domain.entity.FeedbackSentiment;
import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackAspectType;
import java.util.List;

/** Provider Structured Output 계약이다. relevant=false면 items는 무시하고 빈 것으로 취급한다. */
public record RestaurantFeedbackAnalysis(boolean relevant, List<Item> items) {
    public record Item(FeedbackCategory category, FeedbackAspectType aspectType, String normalizedAspect, FeedbackOpinionType opinionType, FeedbackSentiment sentiment) { }
}
