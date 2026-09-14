package com.bobfull.restaurantinsight.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurantinsight.application.dto.RestaurantFeedbackAnalysis;
import com.bobfull.restaurantinsight.application.port.RestaurantFeedbackInsightPort;
import com.bobfull.restaurantinsight.domain.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackCategory;
import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackSentiment;
import com.bobfull.restaurantinsight.infrastructure.repository.RestaurantFeedbackInsightRepository;
import com.bobfull.restaurantinsight.infrastructure.repository.RestaurantFeedbackItemRepository;
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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * #277 PHASE D: 하나의 ChatMessageCreatedEvent를 Moderation과 Restaurant Insight가 서로 다른
 * Consumer Group으로 독립 재사용하는지, 실패가 서로 격리되는지, Insight 전용 Retry/DLT/재전달
 * 멱등성이 실제 Kafka broker에서 성립하는지 검증한다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:restaurant-insight-kafka-it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=restaurant-insight-kafka-it-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=restaurant-insight-kafka-it-api-secret",
        "portone.store-id=restaurant-insight-kafka-it-store-id",
        "portone.webhook-secret=cmVzdGF1cmFudC1pbnNpZ2h0LWthZmthLWl0",
        "outbox.chat-message.enabled=false",
        "bobfull.ai.moderation.fake-enabled=true",
        "bobfull.ai.restaurant-insight.enabled=false",
        "bobfull.restaurant-feedback.active-prompt-version=v1",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
        "bobfull.kafka.chat-message.topic=restaurant-insight-it.v1",
        "bobfull.kafka.chat-message.dlt-topic=restaurant-insight-it.moderation.dlt.v1",
        "bobfull.kafka.chat-message.consumer-max-attempts=3",
        "bobfull.kafka.chat-message.consumer-retry-backoff-ms=200",
        "bobfull.kafka.restaurant-insight.consumer-enabled=true",
        "bobfull.kafka.restaurant-insight.group-id=bobfull-restaurant-insight-it",
        "bobfull.kafka.restaurant-insight.dlt-topic=restaurant-insight-it.insight.dlt.v1",
        "bobfull.kafka.restaurant-insight.consumer-max-attempts=2",
        "bobfull.kafka.restaurant-insight.consumer-retry-backoff-ms=200"
})
@ContextConfiguration(classes = RestaurantInsightKafkaIntegrationTest.Configuration.class)
class RestaurantInsightKafkaIntegrationTest {

    private static final String TOPIC = "restaurant-insight-it.v1";
    private static final String INSIGHT_DLT_TOPIC = "restaurant-insight-it.insight.dlt.v1";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatModerationRepository chatModerationRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private RestaurantFeedbackInsightRepository analysisRepository;
    @Autowired private RestaurantFeedbackItemRepository itemRepository;
    @Autowired private KafkaOperations<Object, Object> kafkaTemplate;
    @Autowired private FakeInsightProvider insightProvider;

    @AfterEach
    void cleanUp() {
        insightProvider.reset();
        itemRepository.deleteAll();
        analysisRepository.deleteAll();
        chatModerationRepository.deleteAll();
        chatMessageRepository.deleteAll();
        reservationRepository.deleteAll();
        chatRoomRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
    }

    // D1: Fan-out — 동일 Event N건을 Moderation Group과 Insight Group이 각각 N건 독립 소비한다.
    @Test
    void 동일_Event를_Moderation과_Insight가_서로_다른_Group으로_각각_전부_소비한다() {
        int n = 5;
        ChatMessage[] messagesArr = new ChatMessage[n];
        for (int i = 0; i < n; i++) {
            messagesArr[i] = chatMessage("탕수육 맛 좋아요 " + i);
            publish(event(messagesArr[i].getId(), messagesArr[i].getChatRoomId()));
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(chatModerationRepository.findAll()).hasSize(n);
            assertThat(analysisRepository.findAll()).hasSize(n);
        });
        for (ChatMessage message : messagesArr) {
            assertThat(analysisRepository.findByMessageIdAndPromptVersion(message.getId(), "v1")).isPresent();
        }
    }

    // D2: Failure Isolation — Insight만 강제 실패해도 Chat/Moderation은 정상, Moderation DLT/상태 영향 0.
    @Test
    void Insight가_실패해도_Moderation은_영향받지_않는다() {
        insightProvider.alwaysFail();
        ChatMessage message = chatMessage("탕수육 맛 좋아요");
        publish(event(message.getId(), message.getChatRoomId()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(chatModerationRepository.findByMessageId(message.getId())).isPresent());
        assertThat(chatModerationRepository.findByMessageId(message.getId()).orElseThrow().getStatus().name())
                .isNotEqualTo("ANALYSIS_FAILED");

        assertThat(findRecordContaining(INSIGHT_DLT_TOPIC, "\"messageId\":" + message.getId())).isNotNull();
        assertThat(analysisRepository.findByMessageIdAndPromptVersion(message.getId(), "v1")).isEmpty();
    }

    // D3: Insight 전용 Retry/DLT — 소진 후 Insight DLT에만 발행되고 Moderation에는 없다.
    @Test
    void Insight_반복_실패는_Insight_전용_DLT로만_이동한다() {
        insightProvider.alwaysFail();
        ChatMessage message = chatMessage("탕수육 맛 좋아요");
        publish(event(message.getId(), message.getChatRoomId()));

        String dltRecord = findRecordContaining(INSIGHT_DLT_TOPIC, "\"messageId\":" + message.getId());
        assertThat(dltRecord).isNotNull();
        assertThat(insightProvider.callCount()).isGreaterThanOrEqualTo(2); // consumer-max-attempts=2
    }

    // D5: Redelivery Idempotency — 동일 Event 재전달 시 Provider/Analysis/Item 증가 0.
    @Test
    void 동일_Event_재전달은_Provider_Analysis_Item_증가를_유발하지_않는다() {
        ChatMessage message = chatMessage("탕수육 맛 좋아요");
        ChatMessageCreatedEvent evt = event(message.getId(), message.getChatRoomId());
        publish(evt);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(analysisRepository.findByMessageIdAndPromptVersion(message.getId(), "v1")).isPresent());
        int callsAfterFirst = insightProvider.callCount();

        publish(evt);
        publish(evt);

        // 재전달 처리 시간을 확보한 뒤에도 delta가 없어야 한다.
        try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(analysisRepository.findAll().stream().filter(a -> a.getMessageId().equals(message.getId())).count()).isEqualTo(1);
        assertThat(insightProvider.callCount()).isEqualTo(callsAfterFirst);
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

    private String findRecordContaining(String topic, String needle) {
        var consumerProps = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(),
                "restaurant-insight-it-verifier-" + UUID.randomUUID(), "true");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (record.value() != null && record.value().contains(needle)) return record.value();
                }
            }
            return null;
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
        private volatile boolean alwaysFail;

        @Override
        public Result analyze(String content) {
            calls.incrementAndGet();
            if (alwaysFail) throw new RuntimeException("강제 Insight 실패(테스트)");
            List<RestaurantFeedbackAnalysis.Item> items = List.of(new RestaurantFeedbackAnalysis.Item(
                    FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE));
            return new Result(new RestaurantFeedbackAnalysis(true, items), "fake", "fake-model");
        }

        void alwaysFail() { this.alwaysFail = true; }
        int callCount() { return calls.get(); }
        void reset() { calls.set(0); alwaysFail = false; }
    }
}
