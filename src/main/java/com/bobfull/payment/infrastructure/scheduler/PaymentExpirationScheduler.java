package com.bobfull.payment.infrastructure.scheduler;

import com.bobfull.payment.application.service.PaymentExpirationProcessor;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 만료 시각이 지난 READY 결제를 배치로 찾아 개별 만료 처리한다.
@Component
@ConditionalOnProperty(prefix = "payment.expiration", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class PaymentExpirationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentExpirationProcessor processor;
    private final Clock clock;
    private final int batchSize;

    public PaymentExpirationScheduler(
            PaymentRepository paymentRepository,
            PaymentExpirationProcessor processor,
            Clock clock,
            @Value("${payment.expiration.batch-size:100}") int batchSize
    ) {
        this.paymentRepository = paymentRepository;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    // 한 건의 실패가 다음 만료 후보 처리를 막지 않도록 개별 실행한다.
    @Scheduled(fixedDelayString = "${payment.expiration.fixed-delay:60000}")
    public void expireReadyPayments() {
        paymentRepository.findExpirationCandidateIds(PaymentStatus.READY, clock.instant(), PageRequest.of(0, batchSize))
                .forEach(paymentInternalId -> {
                    try {
                        processor.expire(paymentInternalId);
                    } catch (RuntimeException exception) {
                        log.warn("event=PAYMENT_EXPIRATION_FAILED paymentInternalId={} reason={}",
                                paymentInternalId, exception.toString(), exception);
                    }
                });
    }
}
