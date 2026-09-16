package com.bobfull.restaurantinsight.presentation.controller;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.restaurantinsight.application.service.RestaurantFeedbackInsightService;
import com.bobfull.restaurantinsight.presentation.response.RestaurantFeedbackInsightListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// OWNER에게 원문 없는 식당 피드백 집계만 제공한다.
@RestController
@RequestMapping("/api/owner/restaurants")
@RequiredArgsConstructor
public class RestaurantFeedbackInsightController {

    private final RestaurantFeedbackInsightService feedbackInsightService;

    @GetMapping("/{restaurantId}/feedback-insights")
    public ApiResponse<RestaurantFeedbackInsightListResponse> getFeedbackInsights(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId
    ) {
        return ApiResponse.success(feedbackInsightService.getOwnerInsights(authMember.id(), restaurantId));
    }
}
