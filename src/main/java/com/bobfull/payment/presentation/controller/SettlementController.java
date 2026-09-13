package com.bobfull.payment.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.payment.presentation.dto.ExpectedSettlementResponse;
import com.bobfull.payment.presentation.dto.SettlementReservationDetailResponse;
import com.bobfull.payment.presentation.dto.SettlementReservationResponse;
import com.bobfull.payment.application.service.SettlementQueryService;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner")
public class SettlementController {

    private final SettlementQueryService settlementQueryService;

    public SettlementController(SettlementQueryService settlementQueryService) {
        this.settlementQueryService = settlementQueryService;
    }

    @GetMapping("/restaurants/{restaurantId}/settlements/expected")
    public ApiResponse<ExpectedSettlementResponse> getExpectedSettlement(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        return ApiResponse.success(settlementQueryService.getExpectedSettlement(authMember.id(), restaurantId, startDate, endDate));
    }

    @GetMapping("/restaurants/{restaurantId}/settlements/reservations")
    public ApiResponse<PageResponse<SettlementReservationResponse>> getReservationSettlements(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(settlementQueryService.getReservationSettlements(
                authMember.id(), restaurantId, startDate, endDate, pageable));
    }

    @GetMapping("/settlements/reservations/{reservationId}")
    public ApiResponse<SettlementReservationDetailResponse> getReservationSettlement(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId
    ) {
        return ApiResponse.success(settlementQueryService.getReservationSettlement(authMember.id(), reservationId));
    }
}
