package com.bobfull.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurantinsight.port.RestaurantFeedbackInsightPort;
import com.bobfull.restaurantinsight.repository.RestaurantFeedbackInsightRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * #277 PHASE D4: Insight DLT 발행 자체가 실패하면 offset 성공 처리/커밋과 final-failure 지표
 * 증가가 발생하면 안 된다(RestaurantInsightDltRecoverer는 delegate.accept()가 성공한 뒤에만
 * metrics.increment(RESTAURANT_INSIGHT_RETRY_EXHAUSTED)를 호출한다).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:restaurant-insight-dlt-failure-it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=restaurant-insight-dlt-failure-it-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=restaurant-insight-dlt-failure-it-api-secret",
        "portone.store-id=restaurant-insight-dlt-failure-it-store-id",
        "portone.webhook-secret=cmVzdGF1cmFudC1pbnNpZ2h0LWRsdC1mYWlsdXJlLWl0",
        "outbox.chat-message.enabled=false",
        "bobfull.ai.moderation.fake-enabled=true",
        "bobfull.ai.restaurant-insight.enabled=false",
        "bobfull.restaurant-feedback.active-prompt-version=v1",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "bobfull.kafka.restaurant-insight.consumer-enabled=true",
        "bobfull.kafka.restaurant-insight.group-id=bobfull-restaurant-insight-dlt-failure-it",
        "bobfull.kafka.chat-message.topic=restaurant-insight-dlt-failure-it.v1",
        "bobfull.kafka.restaurant-insight.dlt-topic=restaurant-insight-dlt-failure-it.dlt.v1",
        "bobfull.kafka.restaurant-insight.consumer-max-attempts=2",
        "bobfull.kafka.restaurant-insight.consumer-retry-backoff-ms=100"
})
@ContextConfiguration(classes = RestaurantInsightDltPublishFailureIntegrationTest.Configuration.class)
class RestaurantInsightDltPublishFailureIntegrationTest {

    private static final String TOPIC = "restaurant-insight-dlt-failure-it.v1";
    private static final String DLT_TOPIC = "restaurant-insight-dlt-failure-it.dlt.v1";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private RestaurantFeedbackInsightRepository analysisRepository;
    @Autowired private KafkaOperations<Object, Object> kafkaTemplate;
    @Autowired private BusinessMetricRecorder businessMetricRecorder;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @AfterEach
    void cleanUp() {
        analysisRepository.deleteAll();
        chatMessageRepository.deleteAll();
        reservationRepository.deleteAll();
        chatRoomRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
    }

    @Test
    void DLT_발행_자체가_실패하면_final_failure_지표를_증가시키지_않는다() throws Exception {
        Restaurant restaurant = restaurantRepository.save(Restaurant.create(1L, "r", "제주시 애월읍", "한식", "d", "k", 1));
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 2));
        TimeSlot slot = timeSlotRepository.save(TimeSlot.create(table.getId(), Instant.now(), Instant.now().plusSeconds(3600)));
        Reservation reservation = reservationRepository.save(Reservation.create(slot.getId(), 1L));
        ChatRoom room = chatRoomRepository.save(ChatRoom.create(reservation.getId()));
        ChatMessage message = chatMessageRepository.saveAndFlush(ChatMessage.create(room.getId(), 1L, 1L, "탕수육 맛 좋아요"));
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(UUID.randomUUID().toString(), 1, message.getId(), room.getId(), Instant.now());

        double before = counterValue();
        kafkaTemplate.send(TOPIC, event.chatRoomId().toString(), event).get(10, TimeUnit.SECONDS);

        Mockito.verify(kafkaTemplate, Mockito.timeout(10000).atLeastOnce())
                .send(ArgumentMatchers.<ProducerRecord<Object, Object>>argThat(
                        record -> record != null && DLT_TOPIC.equals(record.topic())));

        assertThat(analysisRepository.findByMessageIdAndPromptVersion(message.getId(), "v1")).isEmpty();
        assertThat(counterValue()).isEqualTo(before);
    }

    private double counterValue() {
        var counter = meterRegistry.find(BusinessMetricRecorder.METRIC_NAME)
                .tag("event", BusinessMetricEvent.RESTAURANT_INSIGHT_RETRY_EXHAUSTED.name())
                .counter();
        return counter == null ? 0d : counter.count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary
        RestaurantFeedbackInsightPort alwaysFailingInsightPort() {
            return content -> { throw new RuntimeException("강제 Insight 실패(테스트)"); };
        }

        @Bean @Primary
        KafkaOperations<Object, Object> dltPublishFailingKafkaTemplate(KafkaOperations<Object, Object> delegate) {
            KafkaOperations<Object, Object> mock = Mockito.mock(KafkaOperations.class, delegatesTo(delegate));
            Mockito.doAnswer(invocation -> {
                ProducerRecord<Object, Object> producerRecord = invocation.getArgument(0);
                if (DLT_TOPIC.equals(producerRecord.topic())) {
                    return CompletableFuture.failedFuture(new KafkaException("강제 DLT 발행 실패(테스트)"));
                }
                return delegate.send(producerRecord);
            }).when(mock).send(ArgumentMatchers.<ProducerRecord<Object, Object>>any());
            return mock;
        }
    }
}
