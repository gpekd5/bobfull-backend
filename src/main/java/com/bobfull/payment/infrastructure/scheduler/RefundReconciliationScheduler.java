package com.bobfull.payment.infrastructure.scheduler;

import com.bobfull.payment.application.service.RefundReconciliationProcessor;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.application.port.PortOneRefundPort;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 오래 멈춘 환불을 PortOne 조회로 재확인하고 건별 결과를 후속 처리한다.
@Component
// 예시 설정 파일은 자동 로드되지 않으므로 명시적으로 활성화한 환경에서만 실행한다.
@ConditionalOnProperty(prefix = "payment.refund-reconciliation", name = "enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class RefundReconciliationScheduler {

    private static final Duration LONG_RUNNING_WARN = Duration.ofMinutes(30);
    private static final Duration LONG_RUNNING_ERROR = Duration.ofMinutes(60);
    private static final Duration ALERT_WINDOW = Duration.ofMinutes(10);

    private final RefundRepository refundRepository;
    private final RefundReconciliationProcessor processor;
    private final Clock clock;
    private final int batchSize;
    private final Duration minimumAge;
    private final Duration recheckDelay;
    private final Duration maxAge;
    private final Map<Long, Instant> longRunningRefunds = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lookupFailures = new ConcurrentHashMap<>();

    public RefundReconciliationScheduler(RefundRepository refundRepository, RefundReconciliationProcessor processor,
                                         Clock clock, @Value("${payment.refund-reconciliation.batch-size:20}") int batchSize,
                                         @Value("${payment.refund-reconciliation.minimum-age:10m}") Duration minimumAge,
                                         @Value("${payment.refund-reconciliation.recheck-delay:5m}") Duration recheckDelay,
                                         @Value("${payment.refund-reconciliation.max-age:24h}") Duration maxAge) {
        this.refundRepository = refundRepository;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = batchSize;
        this.minimumAge = minimumAge;
        this.recheckDelay = recheckDelay;
        this.maxAge = maxAge;
    }

    // 한 건의 조회·반영 실패가 다음 재조정 후보를 막지 않도록 개별 실행한다.
    @Scheduled(fixedDelayString = "${payment.refund-reconciliation.fixed-delay:5m}")
    public void reconcileStalledRefunds() {
        Instant now = clock.instant();
        refundRepository.findReconciliationCandidates(List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING),
                now.minus(maxAge), now.minus(minimumAge), now.minus(recheckDelay), PageRequest.of(0, batchSize))
                .forEach(refund -> reconcileOne(refund, now));
    }

    private void reconcileOne(Refund refund, Instant now) {
        logLongRunningRefund(refund, now);
        try {
            PortOneRefundPort.ReconciliationResult result = processor.reconcile(refund);
            if (result.status() == PortOneRefundPort.ReconciliationStatus.AMBIGUOUS) {
                log.warn("event=REFUND_MATCH_AMBIGUOUS refundId={} paymentId={} reason={}",
                        refund.getId(), refund.getPayment().getPaymentId(), result.detail());
            }
        } catch (RefundReconciliationProcessor.RefundLookupException exception) {
            lookupFailures.put(refund.getId(), now);
            log.warn("event=REFUND_LOOKUP_FAILED refundId={} paymentId={} reason={}", refund.getId(),
                    refund.getPayment().getPaymentId(), exception.toString(), exception);
            logMultipleFailures(now);
        } catch (RuntimeException exception) {
            log.error("event=REFUND_RECONCILIATION_REQUIRED level=ERROR refundId={} paymentId={} reason={}",
                    refund.getId(), refund.getPayment().getPaymentId(), exception.toString(), exception);
        }
    }

    private void logLongRunningRefund(Refund refund, Instant now) {
        Duration age = Duration.between(refund.getUpdatedAt(), now);
        if (age.compareTo(LONG_RUNNING_WARN) < 0) {
            return;
        }
        longRunningRefunds.put(refund.getId(), now);
        String level = age.compareTo(LONG_RUNNING_ERROR) >= 0 ? "ERROR" : "WARN";
        if ("ERROR".equals(level)) {
            log.error("event=REFUND_RECONCILIATION_REQUIRED level={} refundId={} paymentId={} status={} requestedAt={} updatedAt={} lastPgCheckedAt={} hasCancellationId={}",
                    level, refund.getId(), refund.getPayment().getPaymentId(), refund.getStatus(), refund.getRequestedAt(),
                    refund.getUpdatedAt(), refund.getLastPgCheckedAt(), refund.getCancellationId() != null);
        } else {
            log.warn("event=REFUND_RECONCILIATION_REQUIRED level={} refundId={} paymentId={} status={} requestedAt={} updatedAt={} lastPgCheckedAt={} hasCancellationId={}",
                    level, refund.getId(), refund.getPayment().getPaymentId(), refund.getStatus(), refund.getRequestedAt(),
                    refund.getUpdatedAt(), refund.getLastPgCheckedAt(), refund.getCancellationId() != null);
        }
        pruneAndLogMultiple(longRunningRefunds, now, "long_running_refunds");
    }

    private void logMultipleFailures(Instant now) {
        pruneAndLogMultiple(lookupFailures, now, "lookup_failures");
    }

    private void pruneAndLogMultiple(Map<Long, Instant> events, Instant now, String kind) {
        events.entrySet().removeIf(entry -> entry.getValue().isBefore(now.minus(ALERT_WINDOW)));
        if (events.size() >= 3) {
            log.error("event=REFUND_RECONCILIATION_REQUIRED level=ERROR kind={} distinctRefundCount={} windowMinutes=10",
                    kind, events.size());
        }
    }
}
