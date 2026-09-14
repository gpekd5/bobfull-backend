package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
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
 * 리뷰 지적(MAJOR 2) 재검증: DLT 발행 자체가 실패하면 ChatModerationDltRecoverer가
 * recordFinalFailure를 호출하지 않아야 한다("복구 완료"로 잘못 확정되면 안 됨).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-moderation-dlt-failure-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=chat-moderation-dlt-failure-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-moderation-dlt-failure-test-api-secret",
        "portone.store-id=chat-moderation-dlt-failure-test-store-id",
        "portone.webhook-secret=Y2hhdC1tb2RlcmF0aW9uLWRsdC1mYWlsdXJlLXRlc3Q=",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
        "bobfull.kafka.chat-message.topic=chat-moderation-dlt-failure-it.v1",
        "bobfull.kafka.chat-message.dlt-topic=chat-moderation-dlt-failure-it.dlt.v1",
        "bobfull.kafka.chat-message.consumer-max-attempts=2",
        "bobfull.kafka.chat-message.consumer-retry-backoff-ms=100"
})
@ContextConfiguration(classes = ChatModerationDltPublishFailureIntegrationTest.Configuration.class)
class ChatModerationDltPublishFailureIntegrationTest {

    private static final String TOPIC = "chat-moderation-dlt-failure-it.v1";
    private static final String DLT_TOPIC = "chat-moderation-dlt-failure-it.dlt.v1";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatModerationRepository chatModerationRepository;
    @Autowired private KafkaOperations<Object, Object> kafkaTemplate;
    @Autowired private AiModerationPort alwaysFailingPort;

    @AfterEach
    void cleanUp() {
        chatModerationRepository.deleteAll();
        chatMessageRepository.deleteAll();
    }

    @Test void DLT_발행_자체가_실패하면_recordFinalFailure를_호출하지_않는다() throws Exception {
        // given
        ChatMessage message = chatMessageRepository.saveAndFlush(ChatMessage.create(1L, 2L, 3L, "DLT 발행 실패 테스트"));
        ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(UUID.randomUUID().toString(), 1, message.getId(), 1L, Instant.now());

        // when: AI는 항상 실패, DLT 발행도 항상 실패하는 상태에서 발행
        kafkaTemplate.send(TOPIC, event.chatRoomId().toString(), event).get(10, TimeUnit.SECONDS);

        // then: DLT 발행 실패 지점까지 도달한 뒤에도 ANALYSIS_FAILED가 기록되지 않아야 한다
        Mockito.verify(kafkaTemplate, Mockito.timeout(5000).atLeastOnce())
                .send(ArgumentMatchers.<ProducerRecord<Object, Object>>argThat(
                        record -> record != null && DLT_TOPIC.equals(record.topic())));
        assertThat(chatModerationRepository.findByMessageId(message.getId())).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary
        AiModerationPort alwaysFailingAiModerationPort() {
            return content -> {
                throw new RuntimeException("강제 AI 실패(테스트)");
            };
        }

        @Bean @Primary
        KafkaOperations<Object, Object> dltPublishFailingKafkaTemplate(KafkaOperations<Object, Object> delegate) {
            KafkaOperations<Object, Object> mock = Mockito.mock(KafkaOperations.class, delegatesTo(delegate));
            // DeadLetterPublishingRecoverer는 send(String,K,V)가 아니라 send(ProducerRecord)를 호출한다.
            Mockito.doAnswer(invocation -> {
                org.apache.kafka.clients.producer.ProducerRecord<Object, Object> producerRecord = invocation.getArgument(0);
                if (DLT_TOPIC.equals(producerRecord.topic())) {
                    return CompletableFuture.failedFuture(new KafkaException("강제 DLT 발행 실패(테스트)"));
                }
                return delegate.send(producerRecord);
            }).when(mock).send(ArgumentMatchers.<org.apache.kafka.clients.producer.ProducerRecord<Object, Object>>any());
            return mock;
        }
    }
}
