package com.bobfull.payment.application.service;

import com.bobfull.payment.application.port.PortOneRefundPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// PortOne 환불 웹훅을 검증한 뒤 내부 환불 상태 전이로 연결한다.
@Service
@RequiredArgsConstructor
public class RefundWebhookService {
    private final RefundCompletionService completionService;
    private final PortOneRefundPort refundPort;

    public void markProcessing(String paymentId, String cancellationId) {
        completionService.markProcessingFromWebhook(paymentId, cancellationId);
    }

    // PortOne에서 완료 상태를 재확인한 Cancelled 웹훅만 내부 완료 처리한다.
    public void complete(String paymentId, String cancellationId) {
        if (!refundPort.isCancellationCompleted(paymentId, cancellationId)) {
            throw new IllegalStateException("PortOne cancellation verification failed");
        }
        completionService.completeFromWebhook(paymentId, cancellationId);
    }
}
