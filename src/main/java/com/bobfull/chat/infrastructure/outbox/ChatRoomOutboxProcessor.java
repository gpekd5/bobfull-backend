package com.bobfull.chat.infrastructure.outbox;

import com.bobfull.chat.application.service.ChatRoomCreationService;
import com.bobfull.outbox.entity.OutboxEventStatus;
import com.bobfull.outbox.entity.OutboxEventType;
import com.bobfull.outbox.repository.OutboxEventRepository;
import com.bobfull.outbox.service.OutboxEventTransactionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** ChatRoom 생성 이벤트만 처리하는 at-least-once Outbox processor다. */
@Service
public class ChatRoomOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChatRoomOutboxProcessor.class);
    static final int MAX_RETRIES = 5;
    static final Duration STALE_PROCESSING_THRESHOLD = Duration.ofMinutes(5);
    private static final List<OutboxEventType> CHAT_ROOM_EVENT_TYPES = List.of(OutboxEventType.CHAT_ROOM_CREATION_REQUESTED);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventTransactionService transactionService;
    private final ChatRoomCreationService chatRoomCreationService;
    private final Clock clock;

    public ChatRoomOutboxProcessor(OutboxEventRepository outboxEventRepository,
                                   OutboxEventTransactionService transactionService,
                                   ChatRoomCreationService chatRoomCreationService, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionService = transactionService;
        this.chatRoomCreationService = chatRoomCreationService;
        this.clock = clock;
    }

    public void process(Long eventId) {
        try {
            transactionService.claim(eventId, CHAT_ROOM_EVENT_TYPES, clock.instant()).ifPresent(this::processClaimed);
        } catch (RuntimeException exception) {
            log.error("event=OUTBOX_PROCESSING_REQUIRED outboxEventId={} eventType=CHAT_ROOM_CREATION_REQUESTED reason={}",
                    eventId, exception.toString(), exception);
        }
    }

    public void signal(Long eventId) {
        process(eventId);
    }

    public void processDueEvents(int batchSize) {
        Instant now = clock.instant();
        outboxEventRepository.findStaleProcessingEventIdsByTypes(OutboxEventStatus.PROCESSING,
                        now.minus(STALE_PROCESSING_THRESHOLD), CHAT_ROOM_EVENT_TYPES, PageRequest.of(0, batchSize))
                .forEach(eventId -> recoverStale(eventId, now));
        outboxEventRepository.findDueEventIdsByTypes(OutboxEventStatus.PENDING, now, CHAT_ROOM_EVENT_TYPES, PageRequest.of(0, batchSize))
                .forEach(this::process);
    }

    private void recoverStale(Long eventId, java.time.Instant now) {
        if (transactionService.recoverStale(eventId, now.minus(STALE_PROCESSING_THRESHOLD), now)) {
            log.warn("event=OUTBOX_STUCK_RECOVERED outboxEventId={} eventType=CHAT_ROOM_CREATION_REQUESTED status=PENDING", eventId);
        }
    }

    private void processClaimed(OutboxEventTransactionService.ClaimedOutboxEvent event) {
        log.info("event=OUTBOX_PROCESSING_STARTED outboxEventId={} eventType={} aggregateType=RESERVATION aggregateId={} attemptCount={} status=PROCESSING",
                event.id(), event.eventType(), event.aggregateId(), event.attemptCount());
        try {
            chatRoomCreationService.createIfAbsent(event.aggregateId());
            if (transactionService.complete(event, clock.instant())) {
                log.info("event=OUTBOX_PROCESSING_COMPLETED outboxEventId={} eventType={} aggregateType=RESERVATION aggregateId={} attemptCount={} status=COMPLETED",
                        event.id(), event.eventType(), event.aggregateId(), event.attemptCount());
            }
        } catch (RuntimeException exception) {
            String errorCode = exception.getClass().getSimpleName();
            OutboxEventTransactionService.FailureResult result = transactionService.fail(event, errorCode,
                    clock.instant(), MAX_RETRIES);
            if (!result.updated()) return;
            if (result.failed()) {
                log.error("event=OUTBOX_PROCESSING_FAILED outboxEventId={} eventType={} aggregateType=RESERVATION aggregateId={} attemptCount={} status=FAILED errorCode={}",
                        event.id(), event.eventType(), event.aggregateId(), result.attemptCount(), errorCode, exception);
            } else {
                log.warn("event=OUTBOX_RETRY_SCHEDULED outboxEventId={} eventType={} aggregateType=RESERVATION aggregateId={} attemptCount={} status=PENDING errorCode={} nextAttemptAt={}",
                        event.id(), event.eventType(), event.aggregateId(), result.attemptCount(), errorCode, result.nextAttemptAt(), exception);
            }
        }
    }
}
