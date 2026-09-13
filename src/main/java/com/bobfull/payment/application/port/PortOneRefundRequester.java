package com.bobfull.payment.application.port;

import java.math.BigDecimal;
import java.time.Instant;

/** 외부 SDK의 환불 응답을 결제 도메인에 필요한 최소 정보로 제한한다. */
public interface PortOneRefundRequester {

    RefundResult request(String paymentId, BigDecimal amount, String reason, String idempotencyKey);

    boolean isCancellationCompleted(String paymentId, String cancellationId);

    /** 환불을 재요청하지 않고 PortOne Payment 조회 결과만 내부 환불과 대조한다. */
    default ReconciliationResult reconcile(String paymentId, String cancellationId, BigDecimal refundAmount,
                                           Instant refundRequestedAt) {
        throw new UnsupportedOperationException("PortOne 환불 재확인 조회를 지원하지 않습니다.");
    }

    record RefundResult(String cancellationId, boolean completed) {
    }

    record ReconciliationResult(ReconciliationStatus status, String cancellationId, Instant cancelledAt,
                                String detail) {
        public static ReconciliationResult completed(String cancellationId, Instant cancelledAt) {
            return new ReconciliationResult(ReconciliationStatus.COMPLETED, cancellationId, cancelledAt, null);
        }
        public static ReconciliationResult processing(String cancellationId) {
            return new ReconciliationResult(ReconciliationStatus.PROCESSING, cancellationId, null, null);
        }
        public static ReconciliationResult notCompleted() {
            return new ReconciliationResult(ReconciliationStatus.NOT_COMPLETED, null, null, null);
        }
        public static ReconciliationResult ambiguous(String detail) {
            return new ReconciliationResult(ReconciliationStatus.AMBIGUOUS, null, null, detail);
        }
    }

    enum ReconciliationStatus { COMPLETED, PROCESSING, NOT_COMPLETED, AMBIGUOUS }

    /** PortOne이 환불을 처리하지 않았음을 명시적으로 응답한 경우에만 사용한다. */
    class ExplicitRefundFailureException extends RuntimeException {
        public ExplicitRefundFailureException(String message) {
            super(message);
        }

        public ExplicitRefundFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
