package com.bobfull.payment.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.payment.presentation.dto.PaymentCompletionResponse;
import com.bobfull.payment.presentation.dto.PaymentDetailResponse;
import com.bobfull.payment.application.service.PaymentCompletionService;
import com.bobfull.payment.application.service.PaymentCompletionTransactionService;
import com.bobfull.payment.application.service.PaymentQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentCompletionService paymentCompletionService;
    private final PaymentQueryService paymentQueryService;

    public PaymentController(PaymentCompletionService paymentCompletionService, PaymentQueryService paymentQueryService) {
        this.paymentCompletionService = paymentCompletionService;
        this.paymentQueryService = paymentQueryService;
    }
    @PostMapping("/{paymentId}/complete")
    public ApiResponse<PaymentCompletionResponse> complete(@AuthenticationPrincipal AuthMember authMember, @PathVariable String paymentId) {
        PaymentCompletionTransactionService.PaymentCompletionResult result =
                paymentCompletionService.complete(paymentId, authMember.id());
        return ApiResponse.success(PaymentCompletionResponse.from(result.payment(), result.reservationId(), result.participationId()));
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentDetailResponse> getMyPayment(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable String paymentId
    ) {
        return ApiResponse.success(paymentQueryService.getMyPayment(authMember.id(), paymentId));
    }
}
