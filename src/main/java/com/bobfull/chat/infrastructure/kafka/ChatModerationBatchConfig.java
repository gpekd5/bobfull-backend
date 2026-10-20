package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.common.exception.CustomException;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import java.util.Collection;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

// Native/Insight 설정을 변경하지 않고 Moderation 전용 poll-batch를 구성한다.
@Configuration
@ConditionalOnProperty(prefix = "bobfull.kafka.chat-message", name = "batch-enabled", havingValue = "true")
public class ChatModerationBatchConfig {
    @Bean
    public ChatModerationBatchRetryProcessor chatModerationBatchRetryProcessor(ChatModerationBatchService service,
            ChatModerationDltRecoverer recoverer,
            @Value("${bobfull.kafka.chat-message.consumer-max-attempts:3}") int maxAttempts) {
        return new ChatModerationBatchRetryProcessor(service, recoverer, maxAttempts);
    }

    @Bean
    public DefaultErrorHandler chatModerationBatchErrorHandler(ChatModerationBatchRetryProcessor retry,
            @Value("${bobfull.kafka.chat-message.consumer-max-attempts:3}") int maxAttempts,
            @Value("${bobfull.kafka.chat-message.consumer-retry-backoff-ms:1000}") long backoffMs) {
        DefaultErrorHandler handler = new ChatModerationBatchErrorHandler(retry, maxAttempts, backoffMs);
        handler.addNotRetryableExceptions(CustomException.class, InvalidChatMessageEventException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> chatModerationBatchKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            ChatModerationBatchRetryProcessor retry,
            @Qualifier("chatModerationBatchErrorHandler") DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        // 공유 ConsumerFactory를 변경하지 않고 이 Listener의 poll만 최대 10건으로 제한한다.
        factory.setContainerCustomizer(container -> {
            container.getContainerProperties().getKafkaConsumerProperties()
                    .putAll(Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10", ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"));
            container.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
                @Override
                public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                    retry.revoke(partitions);
                }

                @Override
                public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                    retry.revoke(partitions);
                }
            });
        });
        return factory;
    }
}
