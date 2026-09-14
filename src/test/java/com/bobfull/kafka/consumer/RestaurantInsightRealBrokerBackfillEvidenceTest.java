package com.bobfull.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurantinsight.dto.RestaurantFeedbackAnalysis;
import com.bobfull.restaurantinsight.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.entity.FeedbackCategory;
import com.bobfull.restaurantinsight.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.entity.FeedbackSentiment;
import com.bobfull.restaurantinsight.port.RestaurantFeedbackInsightPort;
import com.bobfull.restaurantinsight.repository.RestaurantFeedbackInsightRepository;
import com.bobfull.restaurantinsight.repository.RestaurantFeedbackItemRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ContextConfiguration;

/**
 * #277 Evidence C — Testcontainers가 아닌 이 프로젝트의 실제 Docker Kafka broker
 * (docker-compose의 {@code bobfull-kafka}, apache/kafka:3.9.0, localhost:9092)를 대상으로
 * retention 범위 안의 Backfill을 검증한다.
 *
 * <p>합성 데이터만 사용하며 실사용 채팅 데이터를 참조하지 않는다. Insight Listener를
 * {@code auto-startup=false}로 기동해 "Consumer가 없던 시점에 쌓인 Event"를 재현한 뒤,
 * 사전에 존재하지 않음을 확인한 신규 groupId로 Listener Container를 수동 시작해
 * earliest offset부터 Backfill이 실제로 일어나는지 확인한다.</p>
 *
 * <p>이 테스트는 CI에서 기동되지 않는, 이 프로젝트의 로컬 개발용 {@code docker-compose.yml}
 * Kafka broker(localhost:9092)가 실제로 떠 있을 때만 실행하는 Evidence 재현용 테스트다. 기존
 * {@code kafka-evidence} Tag 관례(build.gradle의 기본 {@code test} task는 이 Tag를 제외하고,
 * 별도 {@code kafkaEvidenceTest} task에서만 포함)를 따라 기본 실행·CI에서 제외되며, 추가로
 * {@code RESTAURANT_INSIGHT_LOCAL_BROKER_TEST=true} 환경변수가 명시된 로컬 환경에서만
 * 활성화되도록 이중으로 방어한다(기존 {@code Issue251Step0OpenAiBaselineTest}의
 * {@code @EnabledIfEnvironmentVariable} 관례도 함께 따름).</p>
 */
@org.junit.jupiter.api.Tag("kafka-evidence")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RESTAURANT_INSIGHT_LOCAL_BROKER_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:restaurant-insight-real-broker-backfill-it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=restaurant-insight-real-broker-backfill-it-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=restaurant-insight-real-broker-backfill-it-api-secret",
        "portone.store-id=restaurant-insight-real-broker-backfill-it-store-id",
        "portone.webhook-secret=cmVzdGF1cmFudC1pbnNpZ2h0LXJlYWwtYnJva2VyLWJhY2tmaWxsLWl0",
        "outbox.chat-message.enabled=false",
        "bobfull.ai.moderation.fake-enabled=true",
        "bobfull.ai.restaurant-insight.enabled=false",
        "bobfull.restaurant-feedback.active-prompt-version=v1",
        // 실제 로컬 Docker Kafka broker(Testcontainers 아님)
        "spring.kafka.bootstrap-servers=localhost:9092",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "bobfull.kafka.chat-message.topic=bobfull-evidence-277-c.v1",
        "bobfull.kafka.restaurant-insight.consumer-enabled=true",
        "bobfull.kafka.restaurant-insight.group-id=bobfull-restaurant-insight-evidence-c",
        "bobfull.kafka.restaurant-insight.dlt-topic=bobfull-evidence-277-c.dlt.v1",
        "bobfull.kafka.restaurant-insight.consumer-max-attempts=2",
        "bobfull.kafka.restaurant-insight.consumer-retry-backoff-ms=200",
        // 컨테이너를 수동으로 시작해 "Consumer가 없던 시점에 쌓인 Event"를 재현한다.
        "spring.kafka.listener.auto-startup=false"
})
@ContextConfiguration(classes = RestaurantInsightRealBrokerBackfillEvidenceTest.Configuration.class)
class RestaurantInsightRealBrokerBackfillEvidenceTest {

    private static final String TOPIC = "bobfull-evidence-277-c.v1";

    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private RestaurantFeedbackInsightRepository analysisRepository;
    @Autowired private RestaurantFeedbackItemRepository itemRepository;
    @Autowired private KafkaOperations<Object, Object> kafkaTemplate;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private FakeInsightProvider insightProvider;

    @AfterEach
    void cleanUp() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                container.stop();
            }
        }
        insightProvider.reset();
        itemRepository.deleteAll();
        analysisRepository.deleteAll();
        chatMessageRepository.deleteAll();
        reservationRepository.deleteAll();
        chatRoomRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
    }

    @Test
    void 실제_로컬_Docker_Kafka_broker에서_Offset_없는_신규_group이_retention_범위_안의_Event를_Backfill한다() {
        // given: Insight Listener Container를 명시적으로 멈춰 "Consumer가 없던 시점"을 재현한 뒤
        // 합성 Event 5건을 먼저 쌓아둔다. ContainerFactory는 Boot의
        // ConcurrentKafkaListenerContainerFactoryConfigurer를 거치므로 spring.kafka.listener.auto-startup=false가
        // 정상 적용되지만, 방어적으로 실행 중이면 한 번 더 멈춘다.
        assertThat(registry.getListenerContainers()).hasSize(1);
        MessageListenerContainer insightContainer = registry.getListenerContainers().iterator().next();
        if (insightContainer.isRunning()) {
            insightContainer.stop();
            await().atMost(Duration.ofSeconds(10)).until(() -> !insightContainer.isRunning());
        }

        int n = 5;
        Long[] messageIds = new Long[n];
        for (int i = 0; i < n; i++) {
            ChatMessage message = chatMessage("탕수육 맛 좋아요 backfill-" + i);
            messageIds[i] = message.getId();
            publish(event(message.getId(), message.getChatRoomId()));
        }
        assertThat(analysisRepository.findAll()).isEmpty(); // 아직 아무도 소비하지 않았음

        // when: 이전에 존재하지 않던 신규 groupId(bobfull-restaurant-insight-evidence-c)로 Listener를 시작해
        // earliest offset부터 Backfill을 수행한다.
        insightContainer.start();

        // then
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(analysisRepository.findAll()).hasSize(n);
            assertThat(itemRepository.findAll()).hasSize(n);
        });
        for (Long messageId : messageIds) {
            assertThat(analysisRepository.findByMessageIdAndPromptVersion(messageId, "v1")).isPresent();
        }
        assertThat(insightProvider.callCount()).isEqualTo(n);
    }

    private ChatMessage chatMessage(String content) {
        Restaurant restaurant = restaurantRepository.save(Restaurant.create(1L, "r", "제주시 애월읍", "한식", "d", "k", 1));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 2));
        TimeSlot slot = timeSlotRepository.save(TimeSlot.create(table.getId(), Instant.now(), Instant.now().plusSeconds(3600)));
        Reservation reservation = reservationRepository.save(Reservation.create(slot.getId(), 1L));
        ChatRoom room = chatRoomRepository.save(ChatRoom.create(reservation.getId()));
        return chatMessageRepository.saveAndFlush(ChatMessage.create(room.getId(), 1L, 1L, content));
    }

    private ChatMessageCreatedEvent event(Long messageId, Long chatRoomId) {
        return new ChatMessageCreatedEvent(UUID.randomUUID().toString(), 1, messageId, chatRoomId, Instant.now());
    }

    private void publish(ChatMessageCreatedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.chatRoomId().toString(), event).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary
        FakeInsightProvider fakeInsightProvider() {
            return new FakeInsightProvider();
        }
    }

    static class FakeInsightProvider implements RestaurantFeedbackInsightPort {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Result analyze(String content) {
            calls.incrementAndGet();
            List<RestaurantFeedbackAnalysis.Item> items = List.of(new RestaurantFeedbackAnalysis.Item(
                    FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE));
            return new Result(new RestaurantFeedbackAnalysis(true, items), "fake", "fake-model");
        }

        int callCount() { return calls.get(); }
        void reset() { calls.set(0); }
    }
}
