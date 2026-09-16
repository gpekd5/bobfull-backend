package com.bobfull.payment.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.payment.presentation.response.RefundResponse;
import com.bobfull.payment.application.service.RefundQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 인증 사용자의 환불 상세 조회 HTTP 경계를 제공한다.
@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundQueryService refundQueryService;

    @GetMapping("/{refundId}")
    public ApiResponse<RefundResponse> getMyRefund(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long refundId
    ) {
        return ApiResponse.success(refundQueryService.getMyRefund(authMember.id(), refundId));
    }
}
