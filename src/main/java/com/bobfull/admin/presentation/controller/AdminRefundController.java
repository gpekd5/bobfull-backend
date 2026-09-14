package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.dto.AdminRefundListItemResponse;
import com.bobfull.admin.application.service.AdminRefundQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {

    private final AdminRefundQueryService adminRefundQueryService;

    public AdminRefundController(AdminRefundQueryService adminRefundQueryService) {
        this.adminRefundQueryService = adminRefundQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminRefundListItemResponse>> getRefunds(
            @RequestParam(required = false) String refundStatus,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminRefundQueryService.getRefunds(refundStatus, pageable));
    }
}
