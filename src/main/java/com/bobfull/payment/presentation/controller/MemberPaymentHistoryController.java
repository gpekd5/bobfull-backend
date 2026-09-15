package com.bobfull.payment.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.payment.presentation.response.PaymentListResponse;
import com.bobfull.payment.presentation.response.RefundResponse;
import com.bobfull.payment.application.service.PaymentQueryService;
import com.bobfull.payment.application.service.RefundQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members/me")
@RequiredArgsConstructor
public class MemberPaymentHistoryController {

    private final PaymentQueryService paymentQueryService;
    private final RefundQueryService refundQueryService;

    @GetMapping("/payments")
    public ApiResponse<PageResponse<PaymentListResponse>> getMyPayments(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) String paymentStatus,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(paymentQueryService.getMyPayments(authMember.id(), paymentStatus, pageable));
    }

    @GetMapping("/refunds")
    public ApiResponse<PageResponse<RefundResponse>> getMyRefunds(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) String refundStatus,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(refundQueryService.getMyRefunds(authMember.id(), refundStatus, pageable));
    }
}
