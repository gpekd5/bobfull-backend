package com.bobfull.notification.infrastructure.outbox;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 이메일 주소 대신 수신자 식별자와 성공 결과만 저장하는 Outbox 전송 이력이다. */
@Entity
@Table(name = "email_outbox_delivery",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_outbox_delivery_event_recipient",
                columnNames = {"outbox_event_id", "recipient_member_id"}),
        indexes = @Index(name = "idx_email_outbox_delivery_event_status", columnList = "outbox_event_id, status"))
public class EmailOutboxDelivery extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "email_outbox_delivery_id")
    private Long id;

    @Column(name = "outbox_event_id", nullable = false, updatable = false)
    private Long outboxEventId;
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;
    @Column(name = "reservation_participant_id", nullable = false, updatable = false)
    private Long reservationParticipantId;
    @Column(name = "recipient_member_id", nullable = false, updatable = false)
    private Long recipientMemberId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private EmailDeliveryStatus status;
    @Column(name = "sent_at")
    private Instant sentAt;

    protected EmailOutboxDelivery() {}
    private EmailOutboxDelivery(Long eventId, Long reservationId, Long participantId, Long memberId) {
        this.outboxEventId = eventId; this.reservationId = reservationId;
        this.reservationParticipantId = participantId; this.recipientMemberId = memberId;
        this.status = EmailDeliveryStatus.PENDING;
    }
    public static EmailOutboxDelivery pending(Long eventId, Long reservationId, Long participantId, Long memberId) {
        return new EmailOutboxDelivery(eventId, reservationId, participantId, memberId);
    }
    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public Long getReservationParticipantId() { return reservationParticipantId; }
    public Long getRecipientMemberId() { return recipientMemberId; }
    public EmailDeliveryStatus getStatus() { return status; }
    public void markSent(Instant now) { status = EmailDeliveryStatus.SENT; sentAt = now; }
}
