package com.bobfull.chat.infrastructure.outbox;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.outbox.entity.OutboxEvent;
import com.bobfull.outbox.entity.OutboxEventStatus;
import com.bobfull.outbox.entity.OutboxEventType;
import com.bobfull.outbox.repository.OutboxEventRepository;
import com.bobfull.outbox.service.OutboxEventTransactionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Service;

/** ChatMessage 생성 이벤트를 Kafka로 발행하는 at-least-once Outbox processor다. */
@Service
public class ChatMessageOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageOutboxProcessor.class);
    static final int MAX_RETRIES = 5;
    static final Duration STALE_PROCESSING_THRESHOLD = Duration.ofMinutes(5);
    private static final List<OutboxEventType> CHAT_MESSAGE_EVENT_TYPES = List.of(OutboxEventType.CHAT_MESSAGE_CREATED);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventTransactionService transactionService;
    private final ChatMessageRepository chatMessageRepository;
    private final KafkaOperations<Object, Object> kafkaTemplate;
    private final Clock clock;
    private final String topic;
    private final long ackTimeoutSeconds;
    private final String partitionKeyStrategy;

    public ChatMessageOutboxProcessor(OutboxEventRepository outboxEventRepository,
            OutboxEventTransactionService transactionService, ChatMessageRepository chatMessageRepository,
            KafkaOperations<Object, Object> kafkaTemplate, Clock clock,
            @Value("${bobfull.kafka.chat-message.topic:bobfull.chat.message-created.v1}") String topic,
            @Value("${bobfull.kafka.chat-message.producer-ack-timeout-seconds:10}") long ackTimeoutSeconds,
            @Value("${bobfull.kafka.chat-message.partition-key-strategy:message-id}") String partitionKeyStrategy
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionService = transactionService;
        this.chatMessageRepository = chatMessageRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.topic = topic;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.partitionKeyStrategy = partitionKeyStrategy;
    }

    public void process(Long eventId) {
        try {
            transactionService.claim(eventId, CHAT_MESSAGE_EVENT_TYPES, clock.instant()).ifPresent(this::processClaimed);
        } catch (RuntimeException exception) {
            log.error("event=OUTBOX_PROCESSING_REQUIRED outboxEventId={} eventType=CHAT_MESSAGE_CREATED reason={}",
                    eventId, exception.toString(), exception);
        }
    }

    public void signal(Long eventId) {
        process(eventId);
    }

    public void processDueEvents(int batchSize) {
        Instant now = clock.instant();
        outboxEventRepository.findStaleProcessingEventIdsByTypes(OutboxEventStatus.PROCESSING,
                        now.minus(STALE_PROCESSING_THRESHOLD), CHAT_MESSAGE_EVENT_TYPES, PageRequest.of(0, batchSize))
                .forEach(eventId -> recoverStale(eventId, now));
        outboxEventRepository.findDueEventIdsByTypes(OutboxEventStatus.PENDING, now, CHAT_MESSAGE_EVENT_TYPES, PageRequest.of(0, batchSize))
                .forEach(this::process);
    }

    private void recoverStale(Long eventId, Instant now) {
        if (transactionService.recoverStale(eventId, now.minus(STALE_PROCESSING_THRESHOLD), now)) {
            log.warn("event=OUTBOX_STUCK_RECOVERED outboxEventId={} eventType=CHAT_MESSAGE_CREATED status=PENDING", eventId);
        }
    }

    private void processClaimed(OutboxEventTransactionService.ClaimedOutboxEvent event) {
        log.info("event=OUTBOX_PROCESSING_STARTED outboxEventId={} eventType={} aggregateType=CHAT_MESSAGE aggregateId={} attemptCount={} status=PROCESSING",
                event.id(), event.eventType(), event.aggregateId(), event.attemptCount());
        try {
            publish(event);
            if (transactionService.complete(event, clock.instant())) {
                log.info("event=OUTBOX_PROCESSING_COMPLETED outboxEventId={} eventType={} aggregateType=CHAT_MESSAGE aggregateId={} attemptCount={} status=COMPLETED",
                        event.id(), event.eventType(), event.aggregateId(), event.attemptCount());
            }
        } catch (ExecutionException | TimeoutException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String errorCode = exception.getClass().getSimpleName();
            OutboxEventTransactionService.FailureResult result = transactionService.fail(event, errorCode,
                    clock.instant(), MAX_RETRIES);
            if (!result.updated()) return;
            if (result.failed()) {
                log.error("event=OUTBOX_PROCESSING_FAILED outboxEventId={} eventType={} aggregateType=CHAT_MESSAGE aggregateId={} attemptCount={} status=FAILED errorCode={}",
                        event.id(), event.eventType(), event.aggregateId(), result.attemptCount(), errorCode, exception);
            } else {
                log.warn("event=OUTBOX_RETRY_SCHEDULED outboxEventId={} eventType={} aggregateType=CHAT_MESSAGE aggregateId={} attemptCount={} status=PENDING errorCode={} nextAttemptAt={}",
                        event.id(), event.eventType(), event.aggregateId(), result.attemptCount(), errorCode, result.nextAttemptAt(), exception);
            }
        }
    }

    private void publish(OutboxEventTransactionService.ClaimedOutboxEvent event)
            throws ExecutionException, InterruptedException, TimeoutException {
        ChatMessage message = chatMessageRepository.findById(event.aggregateId())
                .orElseThrow(() -> new IllegalStateException("ChatMessage를 찾을 수 없습니다: " + event.aggregateId()));
        OutboxEvent outboxEvent = outboxEventRepository.findById(event.id())
                .orElseThrow(() -> new IllegalStateException("OutboxEvent를 찾을 수 없습니다: " + event.id()));
        ChatMessageCreatedEvent payload = new ChatMessageCreatedEvent(outboxEvent.getEventId(), 1,
                message.getId(), message.getChatRoomId(), clock.instant());
        String key = "message-id".equals(partitionKeyStrategy)
                ? message.getId().toString()
                : message.getChatRoomId().toString();
        kafkaTemplate.send(topic, key, payload).get(ackTimeoutSeconds, TimeUnit.SECONDS);
    }
}
