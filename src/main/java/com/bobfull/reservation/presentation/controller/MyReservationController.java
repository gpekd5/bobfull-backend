package com.bobfull.reservation.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.reservation.presentation.dto.MyReservationDetailResponse;
import com.bobfull.reservation.presentation.dto.MyReservationListItemResponse;
import com.bobfull.reservation.application.service.MyReservationQueryService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members/me")
public class MyReservationController {

    private final MyReservationQueryService myReservationQueryService;

    public MyReservationController(MyReservationQueryService myReservationQueryService) {
        this.myReservationQueryService = myReservationQueryService;
    }

    @GetMapping("/reservations")
    public ApiResponse<PageResponse<MyReservationListItemResponse>> getMyReservations(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) String reservationStatus,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(
                myReservationQueryService.getMyReservations(authMember.id(), reservationStatus, pageable));
    }

    @GetMapping("/reservations/{reservationId}")
    public ApiResponse<MyReservationDetailResponse> getMyReservationDetail(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId
    ) {
        return ApiResponse.success(
                myReservationQueryService.getMyReservationDetail(authMember.id(), reservationId));
    }
}
