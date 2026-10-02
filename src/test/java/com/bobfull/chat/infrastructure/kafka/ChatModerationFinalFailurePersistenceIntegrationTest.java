package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-moderation-final-failure-it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.consumer.group-id=chat-moderation-final-failure-it",
        "jwt.secret=chat-moderation-final-failure-it-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-moderation-final-failure-it-api-secret",
        "portone.store-id=chat-moderation-final-failure-it-store-id",
        "portone.webhook-secret=Y2hhdC1tb2RlcmF0aW9uLWZpbmFsLWZhaWx1cmUtaXQ=",
        "payment.expiration.enabled=false",
        "payment.refund-reconciliation.enabled=false",
        "outbox.chat-room.enabled=false",
        "outbox.email.enabled=false",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
        "bobfull.kafka.chat-message.topic=chat-moderation-final-failure-it.v1",
        "bobfull.kafka.chat-message.dlt-topic=chat-moderation-final-failure-it.dlt.v1",
        "bobfull.kafka.chat-message.partitions=1",
        "bobfull.kafka.chat-message.consumer-max-attempts=2",
        "bobfull.kafka.chat-message.consumer-retry-backoff-ms=100",
        "bobfull.kafka.restaurant-insight.consumer-enabled=false",
        "bobfull.ai.restaurant-insight.enabled=false"
})
@ContextConfiguration(classes = ChatModerationFinalFailurePersistenceIntegrationTest.Configuration.class)
class ChatModerationFinalFailurePersistenceIntegrationTest {

    private static final String GROUP_ID = "chat-moderation-final-failure-it";
    private static final String TOPIC = "chat-moderation-final-failure-it.v1";
    private static final String DLT_TOPIC = "chat-moderation-final-failure-it.dlt.v1";
    private static final String FAILING_CONTENT = "provider-final-failure-marker";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatModerationRepository chatModerationRepository;
    @Autowired private KafkaOperations<Object, Object> kafkaTemplate;
    @Autowired private SelectiveAiModerationPort aiModerationPort;
    @MockitoSpyBean private ChatModerationService moderationService;

    @Test
    void DLT_발행후_최종상태_기록이_한번_실패하면_원본을_재처리하고_뒤의_레코드도_처리한다() throws Exception {
        // given
        ChatMessage failingMessage = chatMessageRepository.saveAndFlush(
                ChatMessage.create(1L, 2L, 3L, FAILING_CONTENT));
        ChatMessage followingMessage = chatMessageRepository.saveAndFlush(
                ChatMessage.create(1L, 2L, 3L, "provider-success-marker"));
        doThrow(new RuntimeException("강제 recordFinalFailure 실패(테스트)"))
                .doCallRealMethod()
                .when(moderationService).recordFinalFailure(eq(failingMessage.getId()), anyString());

        // when: 같은 partition에 실패 레코드와 뒤따르는 정상 레코드를 순서대로 발행한다.
        var failedSend = kafkaTemplate.send(TOPIC, "same-partition", event(failingMessage.getId()))
                .get(10, TimeUnit.SECONDS);
        var followingSend = kafkaTemplate.send(TOPIC, "same-partition", event(followingMessage.getId()))
                .get(10, TimeUnit.SECONDS);

        TopicPartition sourcePartition = new TopicPartition(TOPIC, failedSend.getRecordMetadata().partition());
        assertThat(aiModerationPort.awaitSecondCycle()).isTrue();
        try {
            // 첫 DLT 발행 뒤 recordFinalFailure가 실패한 시점에는 source offset이 성공 처리되지 않는다.
            assertThat(committedOffset(sourcePartition)).isLessThanOrEqualTo(failedSend.getRecordMetadata().offset());
        } finally {
            aiModerationPort.resumeSecondCycle();
        }

        // then: 첫 final-failure 기록 실패 뒤 원본이 재처리되고, 두 번째 복구에서 최종 상태가 기록된다.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(chatModerationRepository.findByMessageId(failingMessage.getId()))
                    .isPresent().get().extracting(value -> value.getStatus())
                    .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED);
            assertThat(chatModerationRepository.findByMessageId(followingMessage.getId()))
                    .isPresent().get().extracting(value -> value.getStatus())
                    .isEqualTo(ModerationProcessingStatus.SAFE);
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(committedOffset(sourcePartition))
                        .isEqualTo(followingSend.getRecordMetadata().offset() + 1));

        assertThat(aiModerationPort.failingCallCount()).isEqualTo(4);
        assertThat(aiModerationPort.successCallCount()).isEqualTo(1);
        verify(moderationService, times(2)).recordFinalFailure(eq(failingMessage.getId()), anyString());
        assertThat(countDltRecords(failingMessage.getId())).isEqualTo(2);
    }

    private ChatMessageCreatedEvent event(Long messageId) {
        return new ChatMessageCreatedEvent(UUID.randomUUID().toString(), 1, messageId, 1L, Instant.now());
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            return offsets.containsKey(partition) ? offsets.get(partition).offset() : -1L;
        }
    }

    private long countDltRecords(Long messageId) {
        Map<String, Object> consumerProps = new HashMap<>(KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "chat-moderation-final-failure-verifier-" + UUID.randomUUID()));
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        long count = 0;
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(consumerProps)) {
            consumer.subscribe(List.of(DLT_TOPIC));
            Instant deadline = Instant.now().plusSeconds(5);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains("\"messageId\":" + messageId)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        SelectiveAiModerationPort selectiveAiModerationPort() {
            return new SelectiveAiModerationPort();
        }
    }

    static class SelectiveAiModerationPort implements AiModerationPort {
        private final AtomicInteger failingCallCount = new AtomicInteger();
        private final AtomicInteger successCallCount = new AtomicInteger();
        private final CountDownLatch secondCycleStarted = new CountDownLatch(1);
        private final CountDownLatch continueSecondCycle = new CountDownLatch(1);

        @Override
        public AiModerationResult analyze(String content) {
            if (FAILING_CONTENT.equals(content)) {
                int call = failingCallCount.incrementAndGet();
                if (call == 3) {
                    secondCycleStarted.countDown();
                    try {
                        if (!continueSecondCycle.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("두 번째 처리 주기 재개 대기 시간 초과");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
                throw new RuntimeException("강제 AI 실패(테스트)");
            }
            successCallCount.incrementAndGet();
            return new AiModerationResult(
                    new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW),
                    "Fake",
                    "test-model",
                    10L,
                    10L,
                    20L);
        }

        int failingCallCount() {
            return failingCallCount.get();
        }

        int successCallCount() {
            return successCallCount.get();
        }

        boolean awaitSecondCycle() throws InterruptedException {
            return secondCycleStarted.await(10, TimeUnit.SECONDS);
        }

        void resumeSecondCycle() {
            continueSecondCycle.countDown();
        }
    }
}
