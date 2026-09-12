package com.bobfull.outbox.service;

import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.outbox.entity.EmailOutboxDelivery;
import com.bobfull.outbox.entity.OutboxEvent;
import com.bobfull.outbox.entity.OutboxEventType;
import com.bobfull.outbox.repository.EmailOutboxDeliveryRepository;
import com.bobfull.outbox.repository.OutboxEventRepository;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 이메일 발송 의도와 수신자별 멱등 키를 호출자의 핵심 트랜잭션에 함께 저장한다. */
@Service
public class EmailOutboxEventService {
    private final OutboxEventRepository outboxEventRepository;
    private final EmailOutboxDeliveryRepository deliveryRepository;
    private final EmailOutboxSignalDispatcher emailOutboxSignalDispatcher;
    private final Clock clock;

    public EmailOutboxEventService(OutboxEventRepository outboxEventRepository,
                                   EmailOutboxDeliveryRepository deliveryRepository,
                                   EmailOutboxSignalDispatcher emailOutboxSignalDispatcher, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.deliveryRepository = deliveryRepository;
        this.emailOutboxSignalDispatcher = emailOutboxSignalDispatcher;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboxEventType type, Long reservationId, List<ReservationParticipant> participants) {
        if (participants.isEmpty()) return;
        ReservationParticipant aggregate = participants.get(0);
        String aggregateType = switch (type) {
            case EMAIL_RESERVATION_CREATED, EMAIL_PARTICIPATION_COMPLETED -> "RESERVATION_PARTICIPANT";
            case EMAIL_RECRUITMENT_CONFIRMED, EMAIL_RECRUITMENT_CANCELLED -> "RESERVATION";
            default -> throw new IllegalArgumentException("이메일 이벤트 유형이 아닙니다.");
        };
        Long aggregateId = aggregateType.equals("RESERVATION") ? reservationId : aggregate.getId();
        OutboxEvent event = outboxEventRepository.save(
                OutboxEvent.emailNotificationRequested(type, aggregateType, aggregateId, clock.instant()));
        deliveryRepository.saveAll(participants.stream()
                .map(p -> EmailOutboxDelivery.pending(event.getId(), reservationId, p.getId(), p.getMemberId())).toList());
        AfterCommitExecutor.run(() -> emailOutboxSignalDispatcher.dispatch(event.getId()));
    }
}
