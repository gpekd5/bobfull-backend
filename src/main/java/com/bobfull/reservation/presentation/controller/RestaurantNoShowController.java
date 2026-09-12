package com.bobfull.reservation.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.common.security.AuthMember;
import com.bobfull.reservation.presentation.dto.NoShowCustomerResponse;
import com.bobfull.reservation.application.service.NoShowService;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** OWNER의 식당 기준 노쇼 고객 조회를 담당한다(Issue #48 §9-5). */
@RestController
@RequestMapping("/api/owner/restaurants/{restaurantId}/no-shows")
public class RestaurantNoShowController {

    private final NoShowService noShowService;

    public RestaurantNoShowController(NoShowService noShowService) {
        this.noShowService = noShowService;
    }

    @GetMapping
    public ApiResponse<PageResponse<NoShowCustomerResponse>> getNoShowCustomers(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(
                noShowService.getRestaurantNoShows(authMember.id(), restaurantId, startDate, endDate, pageable));
    }
}
