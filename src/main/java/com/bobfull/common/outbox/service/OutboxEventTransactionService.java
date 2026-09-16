package com.bobfull.common.outbox.service;

import com.bobfull.common.outbox.entity.OutboxEvent;
import com.bobfull.common.outbox.entity.OutboxEventStatus;
import com.bobfull.common.outbox.entity.OutboxEventType;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Outbox claim·완료·실패·복구 상태 전이를 짧은 독립 트랜잭션으로 처리한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventTransactionService {

    private final OutboxEventRepository outboxEventRepository;

    // 담당 이벤트 유형만 처리 토큰과 함께 선점해 여러 Processor의 중복 처리를 막는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedOutboxEvent> claim(
            Long eventId,
            List<OutboxEventType> eventTypes,
            Instant now
    ) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || !eventTypes.contains(event.getEventType())) {
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        if (outboxEventRepository.claimByTypes(
                eventId,
                OutboxEventStatus.PENDING,
                OutboxEventStatus.PROCESSING,
                now,
                token,
                eventTypes
        ) == 0) {
            return Optional.empty();
        }
        return Optional.of(new ClaimedOutboxEvent(
                eventId,
                event.getEventType().name(),
                event.getAggregateId(),
                event.getAttemptCount(),
                token
        ));
    }

    // 선점 토큰이 일치하는 처리 건만 완료 상태로 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(ClaimedOutboxEvent event, Instant now) {
        return outboxEventRepository.complete(
                event.id(),
                OutboxEventStatus.PROCESSING,
                OutboxEventStatus.COMPLETED,
                event.token(),
                now
        ) == 1;
    }

    // 실패 횟수에 따라 재시도 시각을 예약하고 한도를 넘으면 최종 실패로 전이한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult fail(ClaimedOutboxEvent event, String errorCode, Instant now, int maxRetries) {
        int attemptCount = event.attemptCount() + 1;
        // 최초 처리 뒤 5회 재시도를 모두 예약해 5·10·20·40·80초 backoff를 적용한다.
        // scheduler 주기(5초)와 맞춰야 backoff가 실제 재시도 간격으로 동작한다.
        boolean failed = attemptCount > maxRetries;
        Instant nextAttemptAt = failed ? now : now.plusSeconds(5L * (1L << (attemptCount - 1)));
        int updated = outboxEventRepository.fail(
                event.id(),
                OutboxEventStatus.PROCESSING,
                failed ? OutboxEventStatus.FAILED : OutboxEventStatus.PENDING,
                event.token(),
                attemptCount,
                nextAttemptAt,
                errorCode
        );
        return new FailureResult(updated == 1, failed, attemptCount, nextAttemptAt);
    }

    // 처리 중 멈춘 claim을 다시 PENDING으로 돌려 Scheduler가 재처리할 수 있게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverStale(Long eventId, Instant cutoff, Instant now) {
        return outboxEventRepository.recoverStale(
                eventId,
                OutboxEventStatus.PROCESSING,
                OutboxEventStatus.PENDING,
                cutoff,
                now
        ) == 1;
    }

    // 최종 실패한 이벤트의 시도 횟수와 오류를 초기화해 수동 재처리를 예약한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryManually(Long eventId, Instant now) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox 이벤트를 찾을 수 없습니다."));
        event.retryManually(now);
        log.info(
                "event=OUTBOX_MANUAL_RETRY_REQUESTED outboxEventId={} eventType={} "
                        + "aggregateType=RESERVATION aggregateId={} attemptCount=0 status=PENDING",
                event.getId(),
                event.getEventType(),
                event.getAggregateId()
        );
    }

    public record ClaimedOutboxEvent(Long id, String eventType, Long aggregateId, int attemptCount, String token) {
    }

    public record FailureResult(boolean updated, boolean failed, int attemptCount, Instant nextAttemptAt) {
    }
}
