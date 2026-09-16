package com.bobfull.notification.infrastructure.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
