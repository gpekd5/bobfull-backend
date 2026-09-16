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
