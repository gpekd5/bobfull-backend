package com.bobfull.payment.infrastructure.portone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookTransactionDataPaid;
import io.portone.sdk.server.webhook.WebhookTransactionPaid;
import io.portone.sdk.server.webhook.WebhookVerifier;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortOneSdkWebhookVerifierTest {

    @Mock private WebhookVerifier webhookVerifier;

    @Test
    void 공식SDK에_원본Body와_서명헤더를_전달하고_결제식별자를_반환한다() throws Exception {
        WebhookTransactionPaid paid = new WebhookTransactionPaid(Instant.parse("2026-07-30T00:00:00Z"),
                new WebhookTransactionDataPaid("payment-id", "store-id", "transaction-id"));
        when(webhookVerifier.verify("raw-body", "id", "signature", "timestamp")).thenReturn(paid);

        var event = new PortOneSdkWebhookVerifier(webhookVerifier)
                .verify("raw-body", "id", "signature", "timestamp");

        assertThat(event.paymentId()).isEqualTo("payment-id");
        verify(webhookVerifier).verify("raw-body", "id", "signature", "timestamp");
    }

    @Test
    void SDK_서명검증예외를_그대로_전파한다() throws Exception {
        WebhookVerificationException exception = new WebhookVerificationException("invalid", null);
        when(webhookVerifier.verify("raw-body", "id", "signature", "timestamp")).thenThrow(exception);

        assertThatThrownBy(() -> new PortOneSdkWebhookVerifier(webhookVerifier)
                .verify("raw-body", "id", "signature", "timestamp"))
                .isSameAs(exception);
    }
}
