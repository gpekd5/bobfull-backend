package com.bobfull.payment.infrastructure.adapter;

import com.bobfull.payment.application.port.PortOneRefundRequester;
import io.portone.sdk.server.PortOneClient;
import io.portone.sdk.server.payment.PaymentCancellation;
import io.portone.sdk.server.payment.PaidPayment;
import io.portone.sdk.server.payment.CancelledPayment;
import com.bobfull.payment.infrastructure.config.PortOneProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class PortOneRefundGatewayAdapter implements PortOneRefundRequester {
    private final PortOneClient portOneClient;
    private final RestClient restClient;
    private final PortOneProperties properties;

    @Autowired
    public PortOneRefundGatewayAdapter(PortOneClient portOneClient, RestClient portOneRestClient, PortOneProperties properties) {
        this.portOneClient = portOneClient;
        this.restClient = portOneRestClient;
        this.properties = properties;
    }

    PortOneRefundGatewayAdapter(PortOneClient portOneClient) {
        this(portOneClient, null, null);
    }

    @Override
    public RefundResult request(String paymentId, BigDecimal amount, String reason, String idempotencyKey) {
        Map<?, ?> response = restClient.post().uri("/payments/{paymentId}/cancel", paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "PortOne " + properties.apiSecret())
                .header("Idempotency-Key", "\"" + idempotencyKey + "\"")
                .body(new CancelRequest(properties.storeId(), amount.longValueExact(), reason))
                .retrieve().body(Map.class);
        Object cancellationValue = response == null ? null : response.get("cancellation");
        Map<?, ?> cancellation = cancellationValue instanceof Map<?, ?> value ? value : null;
        String cancellationId = cancellation == null ? null : (String) cancellation.get("id");
        String status = cancellation == null ? null : (String) cancellation.get("status");
        if (cancellationId == null || status == null) throw new IllegalStateException("PortOne cancellation response is incomplete");
        return switch (status) {
            case "SUCCEEDED" -> new RefundResult(cancellationId, true);
            case "REQUESTED" -> new RefundResult(cancellationId, false);
            case "FAILED" -> throw new ExplicitRefundFailureException("PortOne explicitly rejected the refund");
            default -> throw new IllegalStateException("PortOne cancellation response is unrecognized");
        };
    }

    private record CancelRequest(String storeId, long amount, String reason) { }

    static RefundResult toRefundResult(PaymentCancellation cancellation) {
        if (!(cancellation instanceof PaymentCancellation.Recognized recognized)) {
            throw new IllegalStateException("PortOne cancellation response is unrecognized");
        }
        return new RefundResult(recognized.getId(), recognized.getCancelledAt() != null);
    }

    @Override
    public boolean isCancellationCompleted(String paymentId, String cancellationId) {
        io.portone.sdk.server.payment.Payment payment = portOneClient.getPayment().getPayment(paymentId).join();
        java.util.List<? extends PaymentCancellation> cancellations = payment instanceof PaidPayment paid ? paid.getCancellations()
                : payment instanceof CancelledPayment cancelled ? cancelled.getCancellations() : java.util.List.of();
        return cancellations.stream().filter(PaymentCancellation.Recognized.class::isInstance)
                .map(PaymentCancellation.Recognized.class::cast)
                .anyMatch(cancellation -> isCompletedCancellation(cancellation, cancellationId));
    }

    @Override
    public ReconciliationResult reconcile(String paymentId, String cancellationId, BigDecimal refundAmount,
                                          Instant refundRequestedAt) {
        io.portone.sdk.server.payment.Payment payment = portOneClient.getPayment().getPayment(paymentId).join();
        List<PaymentCancellation.Recognized> cancellations = cancellations(payment);
        if (cancellationId != null) {
            return reconcileKnownCancellation(cancellations, cancellationId, refundAmount);
        }
        if (!(payment instanceof CancelledPayment)) {
            return cancellations.isEmpty()
                    ? ReconciliationResult.notCompleted()
                    : ReconciliationResult.ambiguous("payment is not fully cancelled");
        }
        List<PaymentCancellation.Recognized> candidates = cancellations.stream()
                .filter(cancellation -> matchesUnknownCancellation(cancellation, refundAmount, refundRequestedAt))
                .toList();
        if (candidates.size() == 1) {
            PaymentCancellation.Recognized candidate = candidates.get(0);
            return ReconciliationResult.completed(candidate.getId(), candidate.getCancelledAt());
        }
        // Payment는 이미 전액 취소로 확정됐으므로, 단일 매칭 후보가 아니면(0건 포함) 이후 재요청·자동
        // 완료 없이도 사람이 즉시 원인을 확인할 수 있게 AMBIGUOUS로 남긴다(#148 리뷰 반영, #141 계약 수정).
        return ReconciliationResult.ambiguous(candidates.isEmpty()
                ? "cancelled payment has no matching candidate"
                : "multiple or mixed cancellations");
    }

    private ReconciliationResult reconcileKnownCancellation(List<PaymentCancellation.Recognized> cancellations,
                                                            String cancellationId, BigDecimal refundAmount) {
        return cancellations.stream().filter(cancellation -> cancellationId.equals(cancellation.getId())).findFirst()
                .map(cancellation -> {
                    if (BigDecimal.valueOf(cancellation.getTotalAmount()).compareTo(refundAmount) != 0) {
                        return ReconciliationResult.ambiguous("stored cancellation amount differs");
                    }
                    return cancellation.getCancelledAt() == null
                            ? ReconciliationResult.processing(cancellationId)
                            : ReconciliationResult.completed(cancellationId, cancellation.getCancelledAt());
                })
                .orElseGet(ReconciliationResult::notCompleted);
    }

    private List<PaymentCancellation.Recognized> cancellations(io.portone.sdk.server.payment.Payment payment) {
        if (payment instanceof PaidPayment paid) {
            return recognizedCancellations(paid.getCancellations());
        }
        if (payment instanceof CancelledPayment cancelled) {
            return recognizedCancellations(cancelled.getCancellations());
        }
        return List.of();
    }

    private List<PaymentCancellation.Recognized> recognizedCancellations(List<? extends PaymentCancellation> cancellations) {
        return cancellations.stream().filter(PaymentCancellation.Recognized.class::isInstance)
                .map(PaymentCancellation.Recognized.class::cast).toList();
    }

    private boolean matchesUnknownCancellation(PaymentCancellation.Recognized cancellation, BigDecimal refundAmount,
                                               Instant refundRequestedAt) {
        if (cancellation.getCancelledAt() == null
                || BigDecimal.valueOf(cancellation.getTotalAmount()).compareTo(refundAmount) != 0
                || cancellation.getRequestedAt() == null
                || cancellation.getRequestedAt().isBefore(refundRequestedAt.minus(Duration.ofMinutes(1)))) {
            return false;
        }
        return cancellation.getTrigger() == null || "API".equals(cancellation.getTrigger().getValue());
    }

    static boolean isCompletedCancellation(PaymentCancellation.Recognized cancellation, String cancellationId) {
        return cancellationId.equals(cancellation.getId()) && cancellation.getCancelledAt() != null;
    }
}
