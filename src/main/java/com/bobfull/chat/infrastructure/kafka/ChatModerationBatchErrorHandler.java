package com.bobfull.chat.infrastructure.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.DefaultBackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.util.backoff.FixedBackOff;

// 공유 실패만 seek-only로 재전달한다. 정상 반환하면 container가 batch를 ACK할 수 있으므로 반드시 재전파한다.
public class ChatModerationBatchErrorHandler extends DefaultErrorHandler {
    private final ChatModerationBatchRetryProcessor retry;
    private final long backoffMs;

    public ChatModerationBatchErrorHandler(ChatModerationBatchRetryProcessor retry, int maxAttempts, long backoffMs) {
        super(retry::recoverIndexed, new FixedBackOff(backoffMs, Math.max(0, maxAttempts - 1)));
        this.retry = retry;
        this.backoffMs = backoffMs;
    }

    @Override
    public void handleBatch(Exception exception, ConsumerRecords<?, ?> records, Consumer<?, ?> consumer,
            MessageListenerContainer container, Runnable invokeListener) {
        boolean indexed = false;
        boolean group = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            indexed |= cause instanceof BatchListenerFailedException;
            if (cause instanceof ChatModerationBatchRetryProcessor.GroupRetryException) {
                group = true;
            }
        }
        if (group || !indexed) {
            records.partitions().forEach(partition -> consumer.seek(partition, records.records(partition).get(0).offset()));
            new DefaultBackOffHandler().onNextBackOff(container, exception, backoffMs);
            throw new KafkaException("MODERATION_BATCH_GROUP_PENDING", getLogLevel(), exception);
        }
        super.handleBatch(exception, records, consumer, container, invokeListener);
    }

    @Override
    public void clearThreadState() {
        super.clearThreadState();
        retry.clearThreadState();
    }
}
