package com.bobfull.payment.presentation.controller;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.application.service.PaymentCompletionService;
import com.bobfull.payment.application.service.RefundWebhookService;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import com.bobfull.payment.application.port.PortOneWebhookVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Set;

@RestController
@RequestMapping("/api/webhooks/portone")
public class PortOneWebhookController {
    private static final Set<PaymentErrorCode> PERMANENT_FAILURES = Set.of(
            PaymentErrorCode.PAYMENT_NOT_FOUND,
            PaymentErrorCode.PAYMENT_VERIFICATION_FAILED,
            PaymentErrorCode.PAYMENT_EXPIRED);
    private static final Logger log = LoggerFactory.getLogger(PortOneWebhookController.class);
    private final PortOneWebhookVerifier webhookVerifier;
    private final PaymentCompletionService paymentCompletionService;
    private final RefundWebhookService refundWebhookService;
    private final BusinessMetricRecorder businessMetricRecorder;
    @Autowired
    public PortOneWebhookController(PortOneWebhookVerifier webhookVerifier, PaymentCompletionService paymentCompletionService,
            RefundWebhookService refundWebhookService, BusinessMetricRecorder businessMetricRecorder) {
        this.webhookVerifier = webhookVerifier;
        this.paymentCompletionService = paymentCompletionService;
        this.refundWebhookService = refundWebhookService;
        this.businessMetricRecorder = businessMetricRecorder;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
            @RequestHeader(value = WebhookVerifier.HEADER_ID, required = false) String id,
            @RequestHeader(value = WebhookVerifier.HEADER_SIGNATURE, required = false) String signature,
            @RequestHeader(value = WebhookVerifier.HEADER_TIMESTAMP, required = false) String timestamp,
            HttpServletRequest request) {
        try {
            if (id == null || signature == null || timestamp == null) {
                log.warn("event=PORTONE_WEBHOOK_SIGNATURE_INVALID reason=MISSING_HEADERS");
                return ResponseEntity.badRequest().build();
            }
            var event = webhookVerifier.verify(rawBody, id, signature, timestamp);
            request.setAttribute("portonePaymentId", event.paymentId());
            request.setAttribute("portoneCancellationId", event.cancellationId());
            if (event.type() == PortOneWebhookVerifier.WebhookEvent.Type.UNSUPPORTED) return ResponseEntity.ok().build();
            if (event.type() == PortOneWebhookVerifier.WebhookEvent.Type.PARTIAL_CANCELLED) {
                log.info("event=PORTONE_PARTIAL_CANCELLED_IGNORED paymentId={} cancellationId={}", event.paymentId(), event.cancellationId());
                return ResponseEntity.ok().build();
            }
            if (event.type() == PortOneWebhookVerifier.WebhookEvent.Type.CANCEL_PENDING) { refundWebhookService.markProcessing(event.paymentId(), event.cancellationId()); return ResponseEntity.ok().build(); }
            if (event.type() == PortOneWebhookVerifier.WebhookEvent.Type.CANCELLED) { refundWebhookService.complete(event.paymentId(), event.cancellationId()); return ResponseEntity.ok().build(); }
            String paymentId = event.paymentId();
            try { paymentCompletionService.completeFromWebhook(paymentId); }
            catch (CustomException e) {
                if (!PERMANENT_FAILURES.contains(e.getErrorCode())) {
                    throw new IllegalStateException("Unclassified webhook business failure", e);
                }
                if (e.getErrorCode() != PaymentErrorCode.PAYMENT_EXPIRED) {
                    log.error("event=PAYMENT_WEBHOOK_PERMANENT_FAILURE paymentId={} reason={}", paymentId,
                            e.getErrorCode().getCode());
                    businessMetricRecorder.increment(BusinessMetricEvent.PAYMENT_WEBHOOK_PERMANENT_FAILURE);
                }
            }
            return ResponseEntity.ok().build();
        } catch (WebhookVerificationException e) {
            log.warn("event=PORTONE_WEBHOOK_SIGNATURE_INVALID reason=VERIFICATION_FAILED");
            return ResponseEntity.badRequest().build();
        }
    }
}
