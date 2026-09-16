package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ContextConfiguration;

/** Kafka broker를 기동하지 않고 listener 설정이 컨테이너에 반영되는지만 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-moderation-consumer-configuration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=localhost:59999",
        "spring.kafka.listener.auto-startup=false",
        "jwt.secret=chat-moderation-consumer-configuration-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-moderation-consumer-configuration-test-api-secret",
        "portone.store-id=chat-moderation-consumer-configuration-test-store-id",
        "portone.webhook-secret=Y2hhdC1tb2RlcmF0aW9uLWNvbnN1bWVyLWNvbmZpZ3VyYXRpb24tdGVzdA==",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=false",
        "bobfull.kafka.chat-message.consumer-concurrency=3",
        "bobfull.ai.moderation.fake-enabled=true"
})
@ContextConfiguration(classes = ChatModerationConsumerConfigurationTest.Configuration.class)
class ChatModerationConsumerConfigurationTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Test
    void consumer_concurrency_설정이_리스너_컨테이너에_반영된다() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();

        assertThat(containers).hasSize(1);
        MessageListenerContainer container = containers.iterator().next();
        assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);
        assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(3);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ReservationChatAccessPort fixedActiveAccessReader() {
            return (reservationId, memberId) -> new ReservationChatAccessPort.ChatAccess(
                    100L, ParticipationStatus.RESERVED, ReservationStatus.RECRUITING);
        }
    }
}
