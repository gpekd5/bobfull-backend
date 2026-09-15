package com.bobfull.payment.infrastructure.portone;

import com.bobfull.payment.application.port.PortOneWebhookVerifier;
import io.portone.sdk.server.webhook.Webhook;
import io.portone.sdk.server.webhook.WebhookTransactionCancelledCancelPending;
import io.portone.sdk.server.webhook.WebhookTransactionCancelledCancelled;
import io.portone.sdk.server.webhook.WebhookTransactionCancelledPartialCancelled;
import io.portone.sdk.server.webhook.WebhookTransactionPaid;
import io.portone.sdk.server.webhook.WebhookVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PortOneSdkWebhookVerifier implements PortOneWebhookVerifier {

    private final WebhookVerifier verifier;

    @Override
    public WebhookEvent verify(String body, String id, String signature, String timestamp)
            throws io.portone.sdk.server.errors.WebhookVerificationException {
        Webhook webhook = verifier.verify(body, id, signature, timestamp);
        if (webhook instanceof WebhookTransactionPaid paid) {
            return new WebhookEvent(WebhookEvent.Type.PAID, paid.getData().getPaymentId(), null);
        }
        if (webhook instanceof WebhookTransactionCancelledCancelPending cancelPending) {
            return new WebhookEvent(
                    WebhookEvent.Type.CANCEL_PENDING,
                    cancelPending.getData().getPaymentId(),
                    cancelPending.getData().getCancellationId()
            );
        }
        if (webhook instanceof WebhookTransactionCancelledCancelled cancelled) {
            return new WebhookEvent(
                    WebhookEvent.Type.CANCELLED,
                    cancelled.getData().getPaymentId(),
                    cancelled.getData().getCancellationId()
            );
        }
        if (webhook instanceof WebhookTransactionCancelledPartialCancelled partialCancelled) {
            return new WebhookEvent(
                    WebhookEvent.Type.PARTIAL_CANCELLED,
                    partialCancelled.getData().getPaymentId(),
                    partialCancelled.getData().getCancellationId()
            );
        }
        return new WebhookEvent(WebhookEvent.Type.UNSUPPORTED, null, null);
    }
}
