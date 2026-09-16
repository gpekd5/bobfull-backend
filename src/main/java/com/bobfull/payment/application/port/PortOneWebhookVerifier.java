package com.bobfull.payment.application.port;

public interface PortOneWebhookVerifier {

    WebhookEvent verify(String rawBody, String id, String signature, String timestamp)
            throws io.portone.sdk.server.errors.WebhookVerificationException;

    record WebhookEvent(Type type, String paymentId, String cancellationId) {

        public enum Type {
            PAID,
            CANCEL_PENDING,
            CANCELLED,
            PARTIAL_CANCELLED,
            UNSUPPORTED
        }

        public WebhookEvent(String paymentId) {
            this(paymentId == null ? Type.UNSUPPORTED : Type.PAID, paymentId, null);
        }
    }
}
