package com.bobfull.chat.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.outbox.entity.OutboxEvent;
import com.bobfull.outbox.entity.OutboxEventStatus;
import com.bobfull.outbox.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/** #59 Scenario B: Kafka 발행 실패 시 Outbox가 재시도로 남고, 복구 후 실제 broker에 발행돼 COMPLETED가 되는지 검증한다. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-message-outbox-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=chat-message-outbox-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-message-outbox-test-api-secret",
        "portone.store-id=chat-message-outbox-test-store-id",
        "portone.webhook-secret=Y2hhdC1tZXNzYWdlLW91dGJveC10ZXN0",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
        "bobfull.kafka.chat-message.topic=chat-message-outbox-it.v1",
        "bobfull.kafka.chat-message.dlt-topic=chat-message-outbox-it.dlt.v1",
        "bobfull.kafka.chat-message.producer-ack-timeout-seconds=5"
})
@ContextConfiguration(classes = ChatMessageOutboxProcessorIntegrationTest.Configuration.class)
class ChatMessageOutboxProcessorIntegrationTest {

    @Container
    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired private ChatMessageOutboxProcessor processor;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private MutableClock clock;
    @Autowired private FailureMode failureMode;

    @AfterEach
    void cleanUp() {
        failureMode.fail = false;
        chatMessageRepository.deleteAll();
        outboxEventRepository.deleteAll();
        clock.set(Instant.parse("2026-08-08T00:00:00Z"));
    }

    @Test
    void PENDING_이벤트를_처리하면_실제_broker에_발행하고_COMPLETED로_기록한다() {
        // given
        ChatMessage message = chatMessage(10L, 1L);
        OutboxEvent event = pendingEvent(message.getId());

        // when
        processor.process(event.getId());

        // then
        assertThat(reload(event).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
        assertThat(findRecordContaining("chat-message-outbox-it.v1", "\"messageId\":" + message.getId()))
                .contains("\"chatRoomId\":10").doesNotContain("content");
    }

    @Test
    void 발행_실패는_backoff_후_재시도해_복구되면_실제_broker에_발행하고_COMPLETED로_기록한다() {
        // given
        ChatMessage message = chatMessage(11L, 2L);
        OutboxEvent event = pendingEvent(message.getId());
        failureMode.fail = true;

        // when
        processor.process(event.getId());
        OutboxEvent afterFailure = reload(event);
        failureMode.fail = false;
        clock.set(afterFailure.getNextAttemptAt());
        processor.process(event.getId());

        // then
        assertThat(afterFailure.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterFailure.getAttemptCount()).isEqualTo(1);
        assertThat(reload(event).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
    }

    private ChatMessage chatMessage(Long roomId, Long memberId) {
        return chatMessageRepository.saveAndFlush(ChatMessage.create(roomId, memberId, memberId, "테스트 메시지"));
    }

    private OutboxEvent pendingEvent(Long messageId) {
        return outboxEventRepository.saveAndFlush(OutboxEvent.chatMessageCreated(messageId, clock.instant()));
    }

    private OutboxEvent reload(OutboxEvent event) {
        return outboxEventRepository.findById(event.getId()).orElseThrow();
    }

    private String findRecordContaining(String topic, String needle) {
        var consumerProps = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(),
                "chat-message-outbox-it-verifier-" + java.util.UUID.randomUUID(), "true");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(consumerProps)) {
            consumer.subscribe(java.util.List.of(topic));
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(java.time.Duration.ofSeconds(1))) {
                    if (record.value().contains(needle)) return record.value();
                }
            }
            throw new AssertionError("토픽 " + topic + "에서 '" + needle + "'를 포함한 레코드를 찾지 못했습니다.");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(Instant.parse("2026-08-08T00:00:00Z")); }
        @Bean FailureMode failureMode() { return new FailureMode(); }

        @Bean @Primary
        KafkaOperations<Object, Object> failureInjectingKafkaTemplate(
                KafkaOperations<Object, Object> delegate, FailureMode failureMode
        ) {
            KafkaOperations<Object, Object> mock = org.mockito.Mockito.mock(KafkaOperations.class,
                    org.mockito.AdditionalAnswers.delegatesTo(delegate));
            org.mockito.Mockito.doAnswer(invocation -> failureMode.fail
                    ? java.util.concurrent.CompletableFuture.failedFuture(new org.springframework.kafka.KafkaException("강제 발행 실패(테스트)"))
                    : delegate.send(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))
            ).when(mock).send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            return mock;
        }
    }

    static class FailureMode {
        private boolean fail;
    }

    static class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
