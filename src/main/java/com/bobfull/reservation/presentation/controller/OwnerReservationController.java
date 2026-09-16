package com.bobfull.reservation.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.reservation.presentation.response.OwnerReservationCancellationResponse;
import com.bobfull.reservation.presentation.response.OwnerReservationDetailResponse;
import com.bobfull.reservation.presentation.response.OwnerReservationParticipantResponse;
import com.bobfull.reservation.presentation.request.ReservationCancellationRequest;
import com.bobfull.reservation.application.service.OwnerReservationCancellationService;
import com.bobfull.reservation.application.service.OwnerReservationQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 식당 소유자에게 예약 상세·참여자 조회와 전체 취소 API를 제공한다.
@RestController
@RequestMapping("/api/owner/reservations/{reservationId}")
@RequiredArgsConstructor
public class OwnerReservationController {

    private final OwnerReservationQueryService ownerReservationQueryService;
    private final OwnerReservationCancellationService ownerReservationCancellationService;

    @GetMapping
    public ApiResponse<OwnerReservationDetailResponse> getReservationDetail(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId
    ) {
        return ApiResponse.success(
                ownerReservationQueryService.getReservationDetail(authMember.id(), reservationId));
    }

    @GetMapping("/participations")
    public ApiResponse<PageResponse<OwnerReservationParticipantResponse>> getParticipants(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(
                ownerReservationQueryService.getParticipants(authMember.id(), reservationId, pageable));
    }

    @PostMapping("/cancel")
    public ApiResponse<OwnerReservationCancellationResponse> cancel(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId,
            @Valid @RequestBody ReservationCancellationRequest request
    ) {
        return ApiResponse.success(
                ownerReservationCancellationService.cancel(authMember.id(), reservationId, request));
    }
}
