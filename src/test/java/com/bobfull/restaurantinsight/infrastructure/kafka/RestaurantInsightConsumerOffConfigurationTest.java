package com.bobfull.restaurantinsight.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ContextConfiguration;

/**
 * #277 PHASE C: Production 기본 계약(consumer-enabled=false)에서 Insight Kafka Listener/Bean 자체가
 * 비활성화되고, 기존 Moderation Listener는 영향받지 않는지 Kafka broker 없이 Context 레벨로 검증한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:restaurant-insight-off-configuration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=localhost:59999",
        "spring.kafka.listener.auto-startup=false",
        "jwt.secret=restaurant-insight-off-configuration-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=restaurant-insight-off-configuration-test-api-secret",
        "portone.store-id=restaurant-insight-off-configuration-test-store-id",
        "portone.webhook-secret=cmVzdGF1cmFudC1pbnNpZ2h0LW9mZi1jb25maWd1cmF0aW9uLXRlc3Q=",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=false",
        "bobfull.kafka.restaurant-insight.consumer-enabled=false",
        "bobfull.ai.restaurant-insight.enabled=false",
        "bobfull.ai.moderation.fake-enabled=true"
})
@ContextConfiguration(classes = RestaurantInsightConsumerOffConfigurationTest.Configuration.class)
class RestaurantInsightConsumerOffConfigurationTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private ApplicationContext context;

    @Test
    void consumer_enabled_false이면_Insight_Listener_없이_Moderation_Listener만_등록된다() {
        assertThat(registry.getListenerContainers()).hasSize(1);
        assertThat(context.getBeansOfType(RestaurantFeedbackInsightConsumer.class)).isEmpty();
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
