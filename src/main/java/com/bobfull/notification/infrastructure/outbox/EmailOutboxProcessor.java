package com.bobfull.notification.infrastructure.outbox;

import com.bobfull.common.outbox.entity.OutboxEventStatus;
import com.bobfull.common.outbox.entity.OutboxEventType;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.common.outbox.service.OutboxEventTransactionService;
import com.bobfull.notification.infrastructure.repository.EmailOutboxDeliveryRepository;
import com.bobfull.reservation.application.service.ReservationNotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

// 공통 Outbox를 선점해 미전송 수신자만 발송하고 실패 건의 재시도를 예약한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOutboxProcessor {

    private static final int MAX_RETRIES = 5;
    private static final Duration STALE_PROCESSING_THRESHOLD = Duration.ofMinutes(5);
    private static final List<OutboxEventType> EMAIL_EVENT_TYPES = List.of(
            OutboxEventType.EMAIL_RESERVATION_CREATED,
            OutboxEventType.EMAIL_PARTICIPATION_COMPLETED,
            OutboxEventType.EMAIL_RECRUITMENT_CONFIRMED,
            OutboxEventType.EMAIL_RECRUITMENT_CANCELLED);

    private final OutboxEventRepository eventRepository;
    private final EmailOutboxDeliveryRepository deliveryRepository;
    private final OutboxEventTransactionService transactionService;
    private final EmailOutboxDeliveryTransactionService deliveryTransactionService;
    private final ReservationNotificationService notificationService;
    private final Clock clock;
    // 커밋 직후 전달된 신호를 동일한 claim 처리 경로로 연결한다.
    public void signal(Long eventId) {
        process(eventId);
    }

    // 처리 가능한 이메일 Outbox를 선점하고 수신자별 전송을 시도한다.
    public void process(Long eventId) {
        try {
            transactionService.claim(eventId, EMAIL_EVENT_TYPES, clock.instant())
                    .ifPresent(this::processClaimed);
        } catch (RuntimeException exception) {
            log.error(
                    "event=OUTBOX_PROCESSING_REQUIRED outboxEventId={} reason={}",
                    eventId,
                    exception.toString(),
                    exception);
        }
    }

    // 만료된 claim을 복구한 뒤 예약 시각이 지난 PENDING 이벤트를 배치 처리한다.
    public void processDueEvents(int batchSize) {
        Instant now = clock.instant();
        eventRepository.findStaleProcessingEventIdsByTypes(
                        OutboxEventStatus.PROCESSING,
                        now.minus(STALE_PROCESSING_THRESHOLD),
                        EMAIL_EVENT_TYPES,
                        PageRequest.of(0, batchSize))
                .forEach(id -> transactionService.recoverStale(
                        id, now.minus(STALE_PROCESSING_THRESHOLD), now));
        eventRepository.findDueEventIdsByTypes(
                        OutboxEventStatus.PENDING,
                        now,
                        EMAIL_EVENT_TYPES,
                        PageRequest.of(0, batchSize))
                .forEach(this::process);
    }

    private void processClaimed(OutboxEventTransactionService.ClaimedOutboxEvent event) {
        try {
            // 이미 SENT인 수신자는 제외해 재처리 시 이메일을 중복 발송하지 않는다.
            boolean failed = false;
            List<EmailOutboxDelivery> deliveries = deliveryRepository
                    .findAllByOutboxEventIdAndStatus(event.id(), EmailDeliveryStatus.PENDING);
            for (EmailOutboxDelivery delivery : deliveries) {
                try {
                    notificationService.sendOutboxEmail(event.eventType(), delivery);
                    deliveryTransactionService.markSent(delivery.getId(), clock.instant());
                } catch (RuntimeException exception) {
                    failed = true;
                    log.warn(
                            "event=EMAIL_OUTBOX_DELIVERY_FAILED outboxEventId={} recipientMemberId={} errorCode={}",
                            event.id(),
                            delivery.getRecipientMemberId(),
                            exception.getClass().getSimpleName());
                }
            }
            if (!failed && !deliveryRepository.existsByOutboxEventIdAndStatus(
                    event.id(), EmailDeliveryStatus.PENDING)) {
                transactionService.complete(event, clock.instant());
                return;
            }
            throw new IllegalStateException("EMAIL_DELIVERY_PENDING");
        } catch (RuntimeException exception) {
            OutboxEventTransactionService.FailureResult result = transactionService.fail(
                    event, exception.getClass().getSimpleName(), clock.instant(), MAX_RETRIES);
            if (result.updated() && result.failed()) {
                log.error(
                        "event=OUTBOX_PROCESSING_FAILED outboxEventId={} eventType={} attemptCount={} status=FAILED errorCode={}",
                        event.id(),
                        event.eventType(),
                        result.attemptCount(),
                        exception.getClass().getSimpleName(),
                        exception);
            }
        }
    }
}
