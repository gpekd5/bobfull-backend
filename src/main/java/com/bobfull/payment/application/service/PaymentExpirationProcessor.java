package com.bobfull.payment.application.service;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentExpirationProcessor {

    private final PaymentRepository paymentRepository;
    private final Clock clock;
    private final BusinessMetricRecorder businessMetricRecorder;


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
