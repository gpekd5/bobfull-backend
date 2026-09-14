package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.application.dto.AiModerationResponse;
import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.dto.ModerationResult;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * #59 Scenario C/D/E + eventVersion 계약 위반 fast path를 실제 Kafka broker로 검증한다.
 * #66 ChatModerationService/ChatModeration은 실제 Bean을 그대로 쓰고, 외부 경계인 AiModerationPort만
 * Fake로 대체해 Kafka Retry/DLT 계약을 결정적으로 검증한다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-moderation-consumer-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=chat-moderation-consumer-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-moderation-consumer-test-api-secret",
        "portone.store-id=chat-moderation-consumer-test-store-id",
        "portone.webhook-secret=Y2hhdC1tb2RlcmF0aW9uLWNvbnN1bWVyLXRlc3Q=",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
        "bobfull.kafka.chat-message.topic=chat-moderation-consumer-it.v1",
        "bobfull.kafka.chat-message.dlt-topic=chat-moderation-consumer-it.dlt.v1",
        "bobfull.kafka.chat-message.consumer-max-attempts=3",
        "bobfull.kafka.chat-message.consumer-retry-backoff-ms=200"
})
@ContextConfiguration(classes = ChatModerationConsumerIntegrationTest.Configuration.class)
class ChatModerationConsumerIntegrationTest {

    private static final String TOPIC = "chat-moderation-consumer-it.v1";
    private static final String DLT_TOPIC = "chat-moderation-consumer-it.dlt.v1";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatModerationRepository chatModerationRepository;
    @Autowired private KafkaOperations<Object, Object> kafkaTemplate;
    @Autowired private FakeAiModerationPort fakePort;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @AfterEach
    void cleanUp() {
        fakePort.reset();
        chatModerationRepository.deleteAll();
        chatMessageRepository.deleteAll();
    }

    @Test
    void 동일_messageId_중복_수신에도_AI_호출과_결과_저장은_한번만_일어난다() {
        // given
        fakePort.succeedWith(ModerationResultType.SAFE, Set.of());
        ChatMessage message = chatMessage("중복 수신 테스트");
        ChatMessageCreatedEvent event = event(message.getId());

        // when
        publish(event);
        publish(event);

        // then
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(chatModerationRepository.findByMessageId(message.getId()))
                        .isPresent().get().extracting(m -> m.getStatus()).isEqualTo(ModerationProcessingStatus.SAFE));
        assertThat(chatModerationRepository.findAll()).hasSize(1);
        assertThat(fakePort.callCount()).isEqualTo(1);
    }

    @Test
    void 일시_실패_후_Retry로_성공하면_ANALYSIS_FAILED_없이_SAFE_또는_FLAGGED가_저장된다() {
        // given
        fakePort.failTimes(1).thenSucceedWith(ModerationResultType.FLAGGED, Set.of(com.bobfull.chat.domain.entity.ModerationCategory.SPAM));
        ChatMessage message = chatMessage("일시 실패 후 성공 테스트");

        // when
        publish(event(message.getId()));

        // then
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(chatModerationRepository.findByMessageId(message.getId()))
                        .isPresent().get().extracting(m -> m.getStatus()).isEqualTo(ModerationProcessingStatus.FLAGGED));
        assertThat(fakePort.callCount()).isEqualTo(2);
    }

    @Test
    void 반복_실패는_DLT로_이동하고_recordFinalFailure로_ANALYSIS_FAILED가_기록된다() {
        // given
        fakePort.alwaysFail();
        ChatMessage message = chatMessage("반복 실패 테스트");

        // when
        publish(event(message.getId()));

        // then
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(chatModerationRepository.findByMessageId(message.getId()))
                        .isPresent().get().extracting(m -> m.getStatus()).isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED));
        assertThat(fakePort.callCount()).isEqualTo(3);
        assertThat(findRecordContaining(DLT_TOPIC, "\"messageId\":" + message.getId())).isNotNull();
    }

    @Test
    void 잘못된_eventVersion은_AI를_호출하지_않고_바로_DLT_경로로_최종_실패를_기록한다() {
        // given
        fakePort.succeedWith(ModerationResultType.SAFE, Set.of());
        ChatMessage message = chatMessage("버전 불일치 테스트");
        ChatMessageCreatedEvent invalidEvent = new ChatMessageCreatedEvent(
                UUID.randomUUID().toString(), 2, message.getId(), message.getChatRoomId(), Instant.now());

        // when
        publish(invalidEvent);

        // then
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(chatModerationRepository.findByMessageId(message.getId()))
                        .isPresent().get().extracting(m -> m.getStatus()).isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED));
        assertThat(fakePort.callCount()).isZero();
    }

    @Test
    void 메시지_처리_후_Kafka_Consumer_Lag_메트릭이_Micrometer에_노출된다() {
        // given
        fakePort.succeedWith(ModerationResultType.SAFE, Set.of());
        ChatMessage message = chatMessage("메트릭 노출 확인 테스트");

        // when
        publish(event(message.getId()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(chatModerationRepository.findByMessageId(message.getId())).isPresent());

        // then: Spring Boot의 Kafka Micrometer 바인더가 별도 코드 없이 Consumer Lag/consume rate를 노출하는지 확인
        java.util.List<String> kafkaMeterNames = meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith("kafka.consumer") || name.startsWith("kafka.producer"))
                .distinct().sorted().toList();
        assertThat(kafkaMeterNames).isNotEmpty();
        assertThat(kafkaMeterNames.stream().anyMatch(name -> name.contains("records.lag") || name.contains("records.consumed"))).isTrue();
    }

    private ChatMessage chatMessage(String content) {
        return chatMessageRepository.saveAndFlush(ChatMessage.create(1L, 2L, 3L, content));
    }

    private ChatMessageCreatedEvent event(Long messageId) {
        return new ChatMessageCreatedEvent(UUID.randomUUID().toString(), 1, messageId, 1L, Instant.now());
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
                "chat-moderation-consumer-it-verifier-" + UUID.randomUUID(), "true");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (record.value() != null && record.value().contains(needle)) return record.value();
                }
            }
            throw new AssertionError("DLT 토픽 " + topic + "에서 '" + needle + "'를 포함한 레코드를 찾지 못했습니다.");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary
        FakeAiModerationPort fakeAiModerationPort() {
            return new FakeAiModerationPort();
        }
    }

    /** #66 AiModerationPort 경계만 대체하는 Fake다. ChatModerationServiceTest의 Fake 패턴을 그대로 따른다. */
    static class FakeAiModerationPort implements AiModerationPort {
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile int failTimes;
        private volatile boolean alwaysFail;
        private volatile ModerationResultType resultType = ModerationResultType.SAFE;
        private volatile Set<com.bobfull.chat.domain.entity.ModerationCategory> categories = Set.of();

        @Override
        public AiModerationResponse analyze(String content) {
            int count = callCount.incrementAndGet();
            if (alwaysFail || count <= failTimes) {
                throw new RuntimeException("강제 AI 실패(테스트)");
            }
            RiskLevel riskLevel = resultType == ModerationResultType.SAFE ? RiskLevel.LOW : RiskLevel.HIGH;
            return new AiModerationResponse(new ModerationResult(resultType, categories, riskLevel),
                    "OpenAI", "test-model", 10L, 10L, 20L);
        }

        FakeAiModerationPort succeedWith(ModerationResultType resultType, Set<com.bobfull.chat.domain.entity.ModerationCategory> categories) {
            this.resultType = resultType;
            this.categories = categories;
            return this;
        }

        FakeAiModerationPort failTimes(int failTimes) {
            this.failTimes = failTimes;
            return this;
        }

        void thenSucceedWith(ModerationResultType resultType, Set<com.bobfull.chat.domain.entity.ModerationCategory> categories) {
            this.resultType = resultType;
            this.categories = categories;
        }

        void alwaysFail() {
            this.alwaysFail = true;
        }

        int callCount() {
            return callCount.get();
        }

        void reset() {
            callCount.set(0);
            failTimes = 0;
            alwaysFail = false;
            resultType = ModerationResultType.SAFE;
            categories = Set.of();
        }
    }
}
