package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

// poll 안에서 동기 처리하고 DB 미완료 원본 record를 ErrorHandler로 돌려준다.
@Component
@ConditionalOnProperty(prefix = "bobfull.kafka.chat-message", name = "batch-enabled", havingValue = "true")
@ConditionalOnExpression("${bobfull.kafka.chat-message.consumer-enabled:true}")
public class ChatModerationBatchConsumer {
    private final ChatModerationBatchService moderation;
    private final ChatModerationBatchMetrics metrics;
    private final ChatModerationMetrics moderationMetrics;
    private final DefaultErrorHandler errorHandler;

    public ChatModerationBatchConsumer(ChatModerationBatchService moderation, ChatModerationBatchMetrics metrics,
            ChatModerationMetrics moderationMetrics,
            @Qualifier("chatModerationBatchErrorHandler") DefaultErrorHandler errorHandler) {
        this.moderation = moderation;
        this.metrics = metrics;
        this.moderationMetrics = moderationMetrics;
        this.errorHandler = errorHandler;
    }

    @KafkaListener(id = "chatModerationBatch", topics = "${bobfull.kafka.chat-message.topic:bobfull.chat.message-created.v1}",
            groupId = "${spring.kafka.consumer.group-id:bobfull-chat-moderation}",
            containerFactory = "chatModerationBatchKafkaListenerContainerFactory",
            concurrency = "${bobfull.kafka.chat-message.consumer-concurrency:1}")
    public void onMessages(List<ConsumerRecord<Object, Object>> records) {
        metrics.poll(records.size());
        List<Long> ids = new ArrayList<>();
        int invalidIndex = -1;
        for (int i = 0; i < records.size(); i++) {
            var record = records.get(i);
            // ErrorHandler와 같은 Consumer thread에서 상태를 조회한다. 성공한 재시도도 빠뜨리지 않는다.
            if (errorHandler.deliveryAttempt(new TopicPartitionOffset(record.topic(), record.partition(), record.offset())) > 1) {
                moderationMetrics.recordKafkaRetry();
            }
            Object value = records.get(i).value();
            if (!(value instanceof ChatMessageCreatedEvent event) || event.eventVersion() != 1 || event.messageId() == null) {
                invalidIndex = i;
                break;
            }
            ids.add(event.messageId());
        }
        try {
            // 유효한 prefix를 처리하기 전에 뒤의 실패 index를 던지면 미처리 offset이 commit될 수 있다.
            moderation.analyze(ids);
        } catch (BatchModerationException exception) {
            throw new BatchListenerFailedException("MODERATION_BATCH_RECORD_FAILED", exception.getCause(), exception.index());
        }
        if (invalidIndex >= 0) {
            throw new BatchListenerFailedException("MODERATION_BATCH_EVENT_INVALID",
                    new InvalidChatMessageEventException("MODERATION_BATCH_EVENT_INVALID"), invalidIndex);
        }
    }
}
