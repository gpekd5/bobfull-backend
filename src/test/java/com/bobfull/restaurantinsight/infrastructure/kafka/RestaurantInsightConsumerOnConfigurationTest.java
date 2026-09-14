package com.bobfull.restaurantinsight.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.port.ReservationChatAccessReader;
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

/**
 * #277 PHASE C: staging/test에서 consumer-enabled=true로 명시적으로 켜면 Moderation과 Insight가
 * 서로 다른 ContainerFactory/ErrorHandler/Bean Qualifier로 독립 등록되고 Bean 충돌이 없는지 검증한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:restaurant-insight-on-configuration-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=localhost:59999",
        "spring.kafka.listener.auto-startup=false",
        "jwt.secret=restaurant-insight-on-configuration-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=restaurant-insight-on-configuration-test-api-secret",
        "portone.store-id=restaurant-insight-on-configuration-test-store-id",
        "portone.webhook-secret=cmVzdGF1cmFudC1pbnNpZ2h0LW9uLWNvbmZpZ3VyYXRpb24tdGVzdA==",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=false",
        "bobfull.kafka.restaurant-insight.consumer-enabled=true",
        "bobfull.kafka.restaurant-insight.consumer-concurrency=2",
        "bobfull.kafka.restaurant-insight.group-id=bobfull-restaurant-insight-it",
        "bobfull.ai.restaurant-insight.enabled=false",
        "bobfull.ai.moderation.fake-enabled=true"
})
@ContextConfiguration(classes = RestaurantInsightConsumerOnConfigurationTest.Configuration.class)
class RestaurantInsightConsumerOnConfigurationTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private RestaurantFeedbackInsightConsumer insightConsumer;

    @Test
    void consumer_enabled_true이면_Moderation과_Insight_두_Listener가_독립_등록된다() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        assertThat(containers).hasSize(2);
        assertThat(containers).allMatch(ConcurrentMessageListenerContainer.class::isInstance);
    }

    // 리뷰 지적(MAJOR): 전용 ContainerFactory가 직접 new된 팩토리라 Boot의
    // ConcurrentKafkaListenerContainerFactoryConfigurer(spring.kafka.listener.* 공통 설정)를 거치지 않으면
    // ack-mode/auto-startup 같은 공통 설정이 두 Consumer 모두에 반영되지 않는다. 재검증한다.
    @Test
    void 전용_ContainerFactory도_공통_listener_속성을_적용받는다() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        for (MessageListenerContainer container : containers) {
            var props = ((ConcurrentMessageListenerContainer<?, ?>) container).getContainerProperties();
            assertThat(props.getAckMode()).isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        }
        assertThat(containers).allMatch(c -> !c.isAutoStartup());
    }

    @Test
    void insight_전용_Consumer_Bean이_정상_주입된다() {
        assertThat(insightConsumer).isNotNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ReservationChatAccessReader fixedActiveAccessReader() {
            return (reservationId, memberId) -> new ReservationChatAccessReader.ChatAccess(
                    100L, ParticipationStatus.RESERVED, ReservationStatus.RECRUITING);
        }
    }
}
