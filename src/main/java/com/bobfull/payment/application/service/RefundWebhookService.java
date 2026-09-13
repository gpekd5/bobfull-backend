package com.bobfull.payment.application.service;

import com.bobfull.payment.application.port.PortOneRefundRequester;
import org.springframework.stereotype.Service;

@Service
public class RefundWebhookService {
    private final RefundCompletionService completionService;
    private final PortOneRefundRequester refundRequester;

    public RefundWebhookService(RefundCompletionService completionService, PortOneRefundRequester refundRequester) {
        this.completionService = completionService;
        this.refundRequester = refundRequester;
    }

    public void markProcessing(String paymentId, String cancellationId) { completionService.markProcessingFromWebhook(paymentId, cancellationId); }
    public void complete(String paymentId, String cancellationId) {
        if (!refundRequester.isCancellationCompleted(paymentId, cancellationId)) {
            throw new IllegalStateException("PortOne cancellation verification failed");
        }
        completionService.completeFromWebhook(paymentId, cancellationId);
    }
}
