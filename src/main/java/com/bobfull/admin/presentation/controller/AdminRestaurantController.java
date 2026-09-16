package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.response.AdminRestaurantDetailResponse;
import com.bobfull.admin.presentation.response.AdminRestaurantListItemResponse;
import com.bobfull.admin.application.service.AdminRestaurantQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/restaurants")
@RequiredArgsConstructor
public class AdminRestaurantController {

    private final AdminRestaurantQueryService adminRestaurantQueryService;

    @GetMapping
    public ApiResponse<PageResponse<AdminRestaurantListItemResponse>> getRestaurants(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String restaurantStatus,
            @RequestParam(required = false) Boolean deleted,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(
                adminRestaurantQueryService.getRestaurants(keyword, restaurantStatus, deleted, pageable));
    }

    @GetMapping("/{restaurantId}")
    public ApiResponse<AdminRestaurantDetailResponse> getRestaurant(@PathVariable Long restaurantId) {
        return ApiResponse.success(adminRestaurantQueryService.getRestaurant(restaurantId));
    }
}
