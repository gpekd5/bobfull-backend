package com.bobfull.payment.application.service;

import com.bobfull.payment.application.port.PortOneRefundPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RefundWebhookService {
    private final RefundCompletionService completionService;
    private final PortOneRefundPort refundPort;

    public void markProcessing(String paymentId, String cancellationId) {
        completionService.markProcessingFromWebhook(paymentId, cancellationId);
    }

    public void complete(String paymentId, String cancellationId) {
        if (!refundPort.isCancellationCompleted(paymentId, cancellationId)) {
            throw new IllegalStateException("PortOne cancellation verification failed");
        }
        completionService.completeFromWebhook(paymentId, cancellationId);
    }
}
