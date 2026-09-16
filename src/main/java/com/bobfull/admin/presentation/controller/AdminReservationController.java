package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.response.AdminReservationListItemResponse;
import com.bobfull.admin.application.service.AdminReservationQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reservations")
@RequiredArgsConstructor
public class AdminReservationController {

    private final AdminReservationQueryService adminReservationQueryService;

    @GetMapping
    public ApiResponse<PageResponse<AdminReservationListItemResponse>> getReservations(
            @RequestParam(required = false) String reservationStatus,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(
                adminReservationQueryService.getReservations(reservationStatus, startDate, endDate, pageable));
    }
}
