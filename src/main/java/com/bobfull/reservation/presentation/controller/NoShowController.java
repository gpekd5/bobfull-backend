package com.bobfull.reservation.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.reservation.presentation.response.NoShowCandidateResponse;
import com.bobfull.reservation.presentation.response.NoShowHistoryResponse;
import com.bobfull.reservation.presentation.response.NoShowProcessResponse;
import com.bobfull.reservation.application.service.NoShowService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 식당 소유자에게 예약 참여자의 노쇼 처리와 이력 조회 API를 제공한다.
@RestController
@RequestMapping("/api/owner/reservations/{reservationId}")
@RequiredArgsConstructor
public class NoShowController {

    private final NoShowService noShowService;

    @GetMapping("/participations/no-show-candidates")
    public ApiResponse<PageResponse<NoShowCandidateResponse>> getCandidates(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(noShowService.getCandidates(authMember.id(), reservationId, pageable));
    }

    @PostMapping("/participations/{participationId}/no-show")
    public ApiResponse<NoShowProcessResponse> markNoShow(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId,
            @PathVariable Long participationId
    ) {
        return ApiResponse.success(noShowService.markNoShow(authMember.id(), reservationId, participationId));
    }

    @DeleteMapping("/participations/{participationId}/no-show")
    public ApiResponse<NoShowProcessResponse> unmarkNoShow(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId,
            @PathVariable Long participationId
    ) {
        return ApiResponse.success(noShowService.unmarkNoShow(authMember.id(), reservationId, participationId));
    }

    @GetMapping("/no-show-histories")
    public ApiResponse<PageResponse<NoShowHistoryResponse>> getHistories(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(noShowService.getHistories(authMember.id(), reservationId, pageable));
    }
}
