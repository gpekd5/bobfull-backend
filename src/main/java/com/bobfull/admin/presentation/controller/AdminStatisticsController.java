package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.response.AdminMemberNoShowRateResponse;
import com.bobfull.admin.presentation.response.AdminOverviewStatisticsResponse;
import com.bobfull.admin.presentation.response.AdminRestaurantStatisticsResponse;
import com.bobfull.admin.application.service.AdminStatisticsQueryService;
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
@RequestMapping("/api/admin/statistics")
@RequiredArgsConstructor
public class AdminStatisticsController {

    private final AdminStatisticsQueryService adminStatisticsQueryService;

    @GetMapping("/overview")
    public ApiResponse<AdminOverviewStatisticsResponse> getOverview() {
        return ApiResponse.success(adminStatisticsQueryService.getOverview());
    }

    @GetMapping("/restaurants")
    public ApiResponse<PageResponse<AdminRestaurantStatisticsResponse>> getRestaurantStatistics(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(
                adminStatisticsQueryService.getRestaurantStatistics(startDate, endDate, pageable));
    }

    @GetMapping("/members/no-show-rates")
    public ApiResponse<PageResponse<AdminMemberNoShowRateResponse>> getMemberNoShowRates(
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminStatisticsQueryService.getMemberNoShowRates(pageable));
    }
}
