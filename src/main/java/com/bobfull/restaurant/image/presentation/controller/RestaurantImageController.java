package com.bobfull.restaurant.image.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.restaurant.image.presentation.request.RestaurantImageUploadUrlRequest;
import com.bobfull.restaurant.image.presentation.response.RestaurantImageUploadUrlResponse;
import com.bobfull.restaurant.image.application.service.RestaurantImageService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner/restaurants/images")
public class RestaurantImageController {

    private final RestaurantImageService restaurantImageService;

    public RestaurantImageController(RestaurantImageService restaurantImageService) {
        this.restaurantImageService = restaurantImageService;
    }

    @PostMapping("/upload-url")
    public ApiResponse<RestaurantImageUploadUrlResponse> createUploadUrl(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody RestaurantImageUploadUrlRequest request
    ) {
        return ApiResponse.success(restaurantImageService.createUploadUrl(authMember.id(), request));
    }
}
