package com.bobfull.common.outbox.service;

import com.bobfull.common.outbox.entity.OutboxEvent;
import com.bobfull.common.outbox.entity.OutboxEventStatus;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Outbox 상태 전이만 짧은 독립 트랜잭션으로 수행해 ChatRoom 저장 동안 행 잠금을 유지하지 않는다. */
@Service
public class OutboxEventTransactionService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventTransactionService.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxEventTransactionService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /** Processor가 담당하지 않는 이벤트를 claim하지 못하게 공통 테이블 경계를 강제한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedOutboxEvent> claim(Long eventId, List<com.bobfull.common.outbox.entity.OutboxEventType> eventTypes, Instant now) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || !eventTypes.contains(event.getEventType())) return Optional.empty();

        String token = UUID.randomUUID().toString();
        if (outboxEventRepository.claimByTypes(eventId, OutboxEventStatus.PENDING, OutboxEventStatus.PROCESSING,
                now, token, eventTypes) == 0) return Optional.empty();
        return Optional.of(new ClaimedOutboxEvent(eventId, event.getEventType().name(), event.getAggregateId(),
                event.getAttemptCount(), token));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(ClaimedOutboxEvent event, Instant now) {
        return outboxEventRepository.complete(event.id(), OutboxEventStatus.PROCESSING, OutboxEventStatus.COMPLETED,
                event.token(), now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult fail(ClaimedOutboxEvent event, String errorCode, Instant now, int maxRetries) {
        int attemptCount = event.attemptCount() + 1;
        // 최초 처리 뒤 5회 재시도를 모두 예약해 5·10·20·40·80초 backoff를 적용한다.
        // scheduler 주기(5초)와 맞춰야 backoff가 실제 재시도 간격으로 동작한다.
        boolean failed = attemptCount > maxRetries;
        Instant nextAttemptAt = failed ? now : now.plusSeconds(5L * (1L << (attemptCount - 1)));
        int updated = outboxEventRepository.fail(event.id(), OutboxEventStatus.PROCESSING,
                failed ? OutboxEventStatus.FAILED : OutboxEventStatus.PENDING, event.token(), attemptCount,
                nextAttemptAt, errorCode);
        return new FailureResult(updated == 1, failed, attemptCount, nextAttemptAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverStale(Long eventId, Instant cutoff, Instant now) {
        return outboxEventRepository.recoverStale(eventId, OutboxEventStatus.PROCESSING, OutboxEventStatus.PENDING,
                cutoff, now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryManually(Long eventId, Instant now) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox 이벤트를 찾을 수 없습니다."));
        event.retryManually(now);
        log.info("event=OUTBOX_MANUAL_RETRY_REQUESTED outboxEventId={} eventType={} aggregateType=RESERVATION aggregateId={} attemptCount=0 status=PENDING",
                event.getId(), event.getEventType(), event.getAggregateId());
    }

    public record ClaimedOutboxEvent(Long id, String eventType, Long aggregateId, int attemptCount, String token) {
    }

    public record FailureResult(boolean updated, boolean failed, int attemptCount, Instant nextAttemptAt) {
    }
}
