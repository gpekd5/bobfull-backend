package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.dto.AdminPaymentListItemResponse;
import com.bobfull.admin.application.service.AdminPaymentQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payments")
public class AdminPaymentController {

    private final AdminPaymentQueryService adminPaymentQueryService;

    public AdminPaymentController(AdminPaymentQueryService adminPaymentQueryService) {
        this.adminPaymentQueryService = adminPaymentQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminPaymentListItemResponse>> getPayments(
            @RequestParam(required = false) String paymentStatus,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminPaymentQueryService.getPayments(paymentStatus, pageable));
    }
}
