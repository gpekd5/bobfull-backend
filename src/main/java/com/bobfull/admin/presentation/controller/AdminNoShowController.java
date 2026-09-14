package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.dto.AdminNoShowListItemResponse;
import com.bobfull.admin.application.service.AdminNoShowQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/no-shows")
public class AdminNoShowController {

    private final AdminNoShowQueryService adminNoShowQueryService;

    public AdminNoShowController(AdminNoShowQueryService adminNoShowQueryService) {
        this.adminNoShowQueryService = adminNoShowQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminNoShowListItemResponse>> getNoShows(
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long restaurantId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminNoShowQueryService.getNoShows(memberId, restaurantId, pageable));
    }
}
