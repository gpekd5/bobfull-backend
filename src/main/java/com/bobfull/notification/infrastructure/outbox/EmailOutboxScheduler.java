package com.bobfull.notification.infrastructure.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 즉시 처리 신호가 유실돼도 남아 있는 이메일 Outbox를 주기적으로 복구·재처리한다.
@Component
@ConditionalOnProperty(prefix = "outbox.email", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmailOutboxScheduler {

    private final EmailOutboxProcessor processor;
    private final int batchSize;

    public EmailOutboxScheduler(
            EmailOutboxProcessor processor,
            @Value("${outbox.email.batch-size:100}") int batchSize) {
        this.processor = processor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.email.fixed-delay:5000}")
    public void processDueEvents() {
        processor.processDueEvents(batchSize);
    }
}
