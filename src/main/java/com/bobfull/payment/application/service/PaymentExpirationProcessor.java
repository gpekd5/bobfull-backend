package com.bobfull.payment.application.service;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentExpirationProcessor {
    private static final Logger log = LoggerFactory.getLogger(PaymentExpirationProcessor.class);

    private final PaymentRepository paymentRepository;
    private final Clock clock;
    private final BusinessMetricRecorder businessMetricRecorder;

    public PaymentExpirationProcessor(
            PaymentRepository paymentRepository,
            Clock clock,
            BusinessMetricRecorder businessMetricRecorder
    ) {
        this.paymentRepository = paymentRepository;
        this.clock = clock;
        this.businessMetricRecorder = businessMetricRecorder;
    }
    // 락 순서: Payment 단독(ADR 0001 "복수 비관적 락의 획득 순서" 참고).
    @Transactional
    public void expire(Long id) {
        paymentRepository.findWithLockById(id).ifPresent(payment -> {
            if (payment.expireIfNeeded(clock.instant())) {
                log.info("event=READY_PAYMENT_EXPIRED paymentInternalId={} paymentId={} memberId={} expiresAt={} afterStatus={}",
                        payment.getId(), payment.getPaymentId(), payment.getMemberId(), payment.getExpiresAt(),
                        payment.getStatus());
                AfterCommitExecutor.run(() -> businessMetricRecorder.increment(BusinessMetricEvent.READY_PAYMENT_EXPIRED));
            }
        });
    }
}
