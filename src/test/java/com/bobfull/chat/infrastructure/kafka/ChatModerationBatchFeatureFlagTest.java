package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.DefaultErrorHandler;

class ChatModerationBatchFeatureFlagTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ChatModerationService.class, () -> mock(ChatModerationService.class))
            .withBean(ChatModerationBatchService.class, () -> mock(ChatModerationBatchService.class))
            .withBean(ChatModerationMetrics.class, () -> mock(ChatModerationMetrics.class))
            .withBean(ChatModerationBatchMetrics.class, () -> mock(ChatModerationBatchMetrics.class))
            .withBean("chatModerationBatchErrorHandler", DefaultErrorHandler.class, DefaultErrorHandler::new)
            .withUserConfiguration(ChatModerationConsumer.class, ChatModerationBatchConsumer.class);

    @Test
    void 기본설정은_Native_Listener만_등록한다() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatModerationConsumer.class);
            assertThat(context).doesNotHaveBean(ChatModerationBatchConsumer.class);
        });
    }

    @Test
    void 명시적_OFF도_기존_Native_Listener만_등록한다() {
        runner.withPropertyValues("bobfull.kafka.chat-message.batch-enabled=false").run(context -> {
            assertThat(context).hasSingleBean(ChatModerationConsumer.class);
            assertThat(context).doesNotHaveBean(ChatModerationBatchConsumer.class);
        });
    }

    @Test
    void ON은_Batch_Listener만_등록하여_이중소비를_방지한다() {
        runner.withPropertyValues("bobfull.kafka.chat-message.batch-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ChatModerationBatchConsumer.class);
            assertThat(context).doesNotHaveBean(ChatModerationConsumer.class);
        });
    }

    @Test
    void Consumer_OFF이면_Batch_ON이어도_Listener가_없다() {
        runner.withPropertyValues("bobfull.kafka.chat-message.batch-enabled=true",
                "bobfull.kafka.chat-message.consumer-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ChatModerationConsumer.class);
            assertThat(context).doesNotHaveBean(ChatModerationBatchConsumer.class);
        });
    }
}
