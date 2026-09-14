package com.bobfull.notification.infrastructure.outbox;

import com.bobfull.common.outbox.entity.OutboxEventStatus;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.common.outbox.service.OutboxEventTransactionService;
import com.bobfull.reservation.application.service.ReservationNotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** 공통 Outbox claim/retry 정책 위에서 수신자별 성공을 보존하는 이메일 processor다. */
@Service
public class EmailOutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxProcessor.class);
    private static final int MAX_RETRIES = 5;
    private static final Duration STALE_PROCESSING_THRESHOLD = Duration.ofMinutes(5);
    private static final List<com.bobfull.common.outbox.entity.OutboxEventType> EMAIL_EVENT_TYPES = List.of(
            com.bobfull.common.outbox.entity.OutboxEventType.EMAIL_RESERVATION_CREATED,
            com.bobfull.common.outbox.entity.OutboxEventType.EMAIL_PARTICIPATION_COMPLETED,
            com.bobfull.common.outbox.entity.OutboxEventType.EMAIL_RECRUITMENT_CONFIRMED,
            com.bobfull.common.outbox.entity.OutboxEventType.EMAIL_RECRUITMENT_CANCELLED);
    private final OutboxEventRepository eventRepository;
    private final EmailOutboxDeliveryRepository deliveryRepository;
    private final OutboxEventTransactionService transactionService;
    private final EmailOutboxDeliveryTransactionService deliveryTransactionService;
    private final ReservationNotificationService notificationService;
    private final Clock clock;

    public EmailOutboxProcessor(OutboxEventRepository eventRepository, EmailOutboxDeliveryRepository deliveryRepository,
                                OutboxEventTransactionService transactionService,
                                EmailOutboxDeliveryTransactionService deliveryTransactionService,
                                ReservationNotificationService notificationService, Clock clock) {
        this.eventRepository = eventRepository; this.deliveryRepository = deliveryRepository;
        this.transactionService = transactionService; this.deliveryTransactionService = deliveryTransactionService; this.notificationService = notificationService; this.clock = clock;
    }
    public void signal(Long eventId) { process(eventId); }
    public void process(Long eventId) {
        try { transactionService.claim(eventId, EMAIL_EVENT_TYPES, clock.instant()).ifPresent(this::processClaimed); }
        catch (RuntimeException e) { log.error("event=OUTBOX_PROCESSING_REQUIRED outboxEventId={} reason={}", eventId, e.toString(), e); }
    }
    public void processDueEvents(int batchSize) {
        Instant now = clock.instant();
        eventRepository.findStaleProcessingEventIdsByTypes(OutboxEventStatus.PROCESSING, now.minus(STALE_PROCESSING_THRESHOLD), EMAIL_EVENT_TYPES, PageRequest.of(0, batchSize))
                .forEach(id -> transactionService.recoverStale(id, now.minus(STALE_PROCESSING_THRESHOLD), now));
        eventRepository.findDueEventIdsByTypes(OutboxEventStatus.PENDING, now, EMAIL_EVENT_TYPES,
                PageRequest.of(0, batchSize)).forEach(this::process);
    }
    private void processClaimed(OutboxEventTransactionService.ClaimedOutboxEvent event) {
        try {
            boolean failed = false;
            for (EmailOutboxDelivery delivery : deliveryRepository.findAllByOutboxEventIdAndStatus(event.id(), EmailDeliveryStatus.PENDING)) {
                try { notificationService.sendOutboxEmail(event.eventType(), delivery); deliveryTransactionService.markSent(delivery.getId(), clock.instant()); }
                catch (RuntimeException exception) { failed = true; log.warn("event=EMAIL_OUTBOX_DELIVERY_FAILED outboxEventId={} recipientMemberId={} errorCode={}", event.id(), delivery.getRecipientMemberId(), exception.getClass().getSimpleName()); }
            }
            if (!failed && !deliveryRepository.existsByOutboxEventIdAndStatus(event.id(), EmailDeliveryStatus.PENDING)) {
                transactionService.complete(event, clock.instant()); return;
            }
            throw new IllegalStateException("EMAIL_DELIVERY_PENDING");
        } catch (RuntimeException exception) {
            OutboxEventTransactionService.FailureResult result = transactionService.fail(event, exception.getClass().getSimpleName(), clock.instant(), MAX_RETRIES);
            if (result.updated() && result.failed()) log.error("event=OUTBOX_PROCESSING_FAILED outboxEventId={} eventType={} attemptCount={} status=FAILED errorCode={}", event.id(), event.eventType(), result.attemptCount(), exception.getClass().getSimpleName(), exception);
        }
    }
}
