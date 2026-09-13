package com.bobfull.payment.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.payment.dto.RefundResponse;
import com.bobfull.payment.service.RefundQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/refunds")
public class RefundController {

    private final RefundQueryService refundQueryService;

    public RefundController(RefundQueryService refundQueryService) {
        this.refundQueryService = refundQueryService;
    }

    @GetMapping("/{refundId}")
    public ApiResponse<RefundResponse> getMyRefund(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long refundId
    ) {
        return ApiResponse.success(refundQueryService.getMyRefund(authMember.id(), refundId));
    }
}
