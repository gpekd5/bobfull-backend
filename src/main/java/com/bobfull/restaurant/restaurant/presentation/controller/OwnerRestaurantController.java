package com.bobfull.restaurant.restaurant.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.restaurant.restaurant.presentation.response.OwnerRestaurantDetailResponse;
import com.bobfull.restaurant.restaurant.presentation.response.OwnerRestaurantListResponse;
import com.bobfull.restaurant.restaurant.presentation.request.RestaurantCreateRequest;
import com.bobfull.restaurant.restaurant.presentation.response.RestaurantIdResponse;
import com.bobfull.restaurant.restaurant.presentation.request.RestaurantUpdateRequest;
import com.bobfull.restaurant.restaurant.application.service.RestaurantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner/restaurants")
@RequiredArgsConstructor
public class OwnerRestaurantController {

    private final RestaurantService restaurantService;

    @PostMapping
    public ResponseEntity<ApiResponse<RestaurantIdResponse>> register(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody RestaurantCreateRequest request
    ) {
        RestaurantIdResponse response = restaurantService.register(authMember.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponse<OwnerRestaurantListResponse>> getMyRestaurants(
            @AuthenticationPrincipal AuthMember authMember,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(restaurantService.getMyRestaurants(authMember.id(), pageable));
    }

    @GetMapping("/{restaurantId}")
    public ApiResponse<OwnerRestaurantDetailResponse> getMyRestaurant(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId
    ) {
        return ApiResponse.success(restaurantService.getMyRestaurant(authMember.id(), restaurantId));
    }

    @PatchMapping("/{restaurantId}")
    public ApiResponse<RestaurantIdResponse> update(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @Valid @RequestBody RestaurantUpdateRequest request
    ) {
        return ApiResponse.success(restaurantService.update(authMember.id(), restaurantId, request));
    }

    @DeleteMapping("/{restaurantId}")
    public ApiResponse<RestaurantIdResponse> delete(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId
    ) {
        return ApiResponse.success(restaurantService.delete(authMember.id(), restaurantId));
    }
}
