package com.bobfull.restaurant.restaurant.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantDetailResponse;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchRequest;
import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchResponse;
import com.bobfull.restaurant.restaurant.application.service.RestaurantService;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/restaurants")
public class RestaurantController {

    private final RestaurantService restaurantService;

    public RestaurantController(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    @GetMapping
    public ApiResponse<PageResponse<RestaurantSearchResponse>> searchRestaurants(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) LocalTime time,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        RestaurantSearchRequest request = new RestaurantSearchRequest(keyword, category, date, time);
        return ApiResponse.success(restaurantService.searchRestaurants(request, pageable));
    }

    @GetMapping("/{restaurantId}")
    public ApiResponse<RestaurantDetailResponse> getRestaurant(@PathVariable Long restaurantId) {
        return ApiResponse.success(restaurantService.getRestaurantDetail(restaurantId));
    }
}
