package com.bobfull.payment.infrastructure.adapter;
import com.bobfull.payment.application.port.PortOneWebhookVerifier;
import io.portone.sdk.server.webhook.*;
import org.springframework.stereotype.Component;
@Component public class PortOneSdkWebhookVerifier implements PortOneWebhookVerifier {
 private final WebhookVerifier verifier; public PortOneSdkWebhookVerifier(WebhookVerifier verifier){this.verifier=verifier;}
 public WebhookEvent verify(String body,String id,String sig,String ts) throws io.portone.sdk.server.errors.WebhookVerificationException {
  Webhook w=verifier.verify(body,id,sig,ts);
  if (w instanceof WebhookTransactionPaid p) return new WebhookEvent(WebhookEvent.Type.PAID, p.getData().getPaymentId(), null);
  if (w instanceof WebhookTransactionCancelledCancelPending p) return new WebhookEvent(WebhookEvent.Type.CANCEL_PENDING, p.getData().getPaymentId(), p.getData().getCancellationId());
  if (w instanceof WebhookTransactionCancelledCancelled p) return new WebhookEvent(WebhookEvent.Type.CANCELLED, p.getData().getPaymentId(), p.getData().getCancellationId());
  if (w instanceof WebhookTransactionCancelledPartialCancelled p) return new WebhookEvent(WebhookEvent.Type.PARTIAL_CANCELLED, p.getData().getPaymentId(), p.getData().getCancellationId());
  return new WebhookEvent(WebhookEvent.Type.UNSUPPORTED, null, null);
 }
}
