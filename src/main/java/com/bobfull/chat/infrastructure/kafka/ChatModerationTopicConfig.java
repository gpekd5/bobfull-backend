package com.bobfull.chat.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** ChatMessage 생성 이벤트와 그 DLT 토픽을 로컬 브로커에 자동 생성한다. */
@Configuration
@ConditionalOnProperty(
        prefix = "bobfull.kafka.chat-message",
        name = "topic-auto-create-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatModerationTopicConfig {

    @Bean
    public NewTopic chatMessageCreatedTopic(
            @Value("${bobfull.kafka.chat-message.topic:bobfull.chat.message-created.v1}") String topic,
            @Value("${bobfull.kafka.chat-message.partitions:3}") int partitions,
            @Value("${bobfull.kafka.chat-message.replicas:1}") short replicas
    ) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic chatMessageCreatedDltTopic(
            @Value("${bobfull.kafka.chat-message.dlt-topic:bobfull.chat.message-created.dlt.v1}") String dltTopic,
            @Value("${bobfull.kafka.chat-message.partitions:3}") int partitions,
            @Value("${bobfull.kafka.chat-message.replicas:1}") short replicas
    ) {
        return TopicBuilder.name(dltTopic).partitions(partitions).replicas(replicas).build();
    }
}
