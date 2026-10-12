package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatModerationConsumerMetricTest {

    @Test
    void DeliveryAttempt가_2부터_Kafka_Retry를_기록하고_DLT_소진_Metric은_기록하지_않는다() {
        // given
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatModerationMetrics metrics = new ChatModerationMetrics(registry);
        ChatModerationService service = mock(ChatModerationService.class);
        ChatModerationConsumer consumer = new ChatModerationConsumer(service, metrics);
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(
                "event-1", 1, 10L, 20L, Instant.parse("2026-10-08T00:00:00Z"));

        // when
        consumer.onChatMessageCreated(event, null);
        consumer.onChatMessageCreated(event, 1);

        // then
        assertThat(retryCount(registry)).isZero();

        // when
        consumer.onChatMessageCreated(event, 2);

        // then
        assertThat(retryCount(registry)).isEqualTo(1.0);

        // when
        consumer.onChatMessageCreated(event, 3);

        // then
        assertThat(retryCount(registry)).isEqualTo(2.0);
        assertThat(registry.find(BusinessMetricRecorder.METRIC_NAME).meter()).isNull();
        verify(service, times(4)).analyze(10L);
    }

    private double retryCount(SimpleMeterRegistry registry) {
        Counter counter = registry.find("bobfull.ai.moderation.kafka.retry").counter();
        return counter == null ? 0.0 : counter.count();
    }
}
