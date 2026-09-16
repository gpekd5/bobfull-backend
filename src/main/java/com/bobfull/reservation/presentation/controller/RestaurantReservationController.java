package com.bobfull.reservation.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.reservation.presentation.response.OwnerReservationListItemResponse;
import com.bobfull.reservation.application.service.OwnerReservationQueryService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 식당 소유자에게 식당별 예약 목록 API를 제공한다.
@RestController
@RequestMapping("/api/owner/restaurants/{restaurantId}/reservations")
@RequiredArgsConstructor
public class RestaurantReservationController {

    private final OwnerReservationQueryService ownerReservationQueryService;

    @GetMapping
    public ApiResponse<PageResponse<OwnerReservationListItemResponse>> getReservations(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String reservationStatus,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(ownerReservationQueryService.getRestaurantReservations(
                authMember.id(), restaurantId, reservationStatus, date, pageable));
    }
}
