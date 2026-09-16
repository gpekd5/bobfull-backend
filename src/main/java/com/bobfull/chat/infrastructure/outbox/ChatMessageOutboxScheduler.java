package com.bobfull.chat.infrastructure.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 즉시 signal 유실이나 재시작 뒤에도 남아 있는 메시지 Outbox를 재처리한다.
@Component
@ConditionalOnProperty(prefix = "outbox.chat-message", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChatMessageOutboxScheduler {

    private final ChatMessageOutboxProcessor processor;
    private final int batchSize;

    public ChatMessageOutboxScheduler(ChatMessageOutboxProcessor processor,
                                       @Value("${outbox.chat-message.batch-size:100}") int batchSize) {
        this.processor = processor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.chat-message.fixed-delay:5000}")
    public void processPendingEvents() {
        processor.processDueEvents(batchSize);
    }
}
