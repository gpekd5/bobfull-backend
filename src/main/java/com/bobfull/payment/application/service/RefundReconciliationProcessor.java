package com.bobfull.payment.application.service;

import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.application.port.PortOneRefundPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** PortOne 조회 결과를 기존 환불 완료 경로에 안전하게 연결한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundReconciliationProcessor {

    private final PortOneRefundPort refundPort;
    private final RefundTransactionService transactionService;
    private final RefundCompletionService completionService;

    public PortOneRefundPort.ReconciliationResult reconcile(Refund refund) {
        PortOneRefundPort.ReconciliationResult result = null;
        RuntimeException failure = null;
        try {
            result = reconcileInternal(refund);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            // 조회 결과와 상태 변경 실패 여부와 무관하게, 실제 PG 조회 시도는 기록한다.
            transactionService.markPgChecked(refund.getId());
        } catch (RuntimeException markFailure) {
            if (failure == null) {
                failure = markFailure;
            } else {
                // 원 실패 원인이 markPgChecked 실패로 대체되어 사라지지 않도록, 여기서는 로그만
                // 남기고 먼저 발생한 실패를 그대로 던진다.
                log.error("event=REFUND_PG_CHECK_MARK_FAILED refundId={} reason={}", refund.getId(),
                        markFailure.toString(), markFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    private PortOneRefundPort.ReconciliationResult reconcileInternal(Refund refund) {
        if (refund.getAmount().compareTo(refund.getPayment().getAmount()) != 0) {
            return PortOneRefundPort.ReconciliationResult.ambiguous("refund amount differs from payment amount");
        }
        PortOneRefundPort.ReconciliationResult result;
        try {
            result = refundPort.reconcile(refund.getPayment().getPaymentId(), refund.getCancellationId(),
                    refund.getAmount(), refund.getRequestedAt());
        } catch (RuntimeException exception) {
            throw new RefundLookupException(exception);
        }
        if (result.status() == PortOneRefundPort.ReconciliationStatus.COMPLETED) {
            completionService.reflectExternalResult(refund.getId(), result.cancellationId(), true);
        } else if (result.status() == PortOneRefundPort.ReconciliationStatus.PROCESSING
                && result.cancellationId() != null) {
            completionService.reflectExternalResult(refund.getId(), result.cancellationId(), false);
        }
        return result;
    }

    public static class RefundLookupException extends RuntimeException {
        RefundLookupException(Throwable cause) {
            super(cause);
        }
    }
}
