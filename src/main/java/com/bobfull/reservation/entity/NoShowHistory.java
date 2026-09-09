package com.bobfull.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * OWNER의 참여자 단위 노쇼 처리·해제 이력이다(docs/30-data/erd.md 4.9).
 * ReservationParticipant의 하위 이력이라 별도 Repository·Entity를 다른 도메인에 노출하지 않는다.
 */
@Entity
@Table(name = "no_show_history")
public class NoShowHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "no_show_history_id")
    private Long id;

    @Column(name = "reservation_participant_id", nullable = false)
    private Long reservationParticipantId;

    @Column(name = "processed_by_member_id", nullable = false)
    private Long processedByMemberId;

    @Column(name = "is_marked", nullable = false)
    private boolean marked;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected NoShowHistory() {
    }

    private NoShowHistory(Long reservationParticipantId, Long processedByMemberId, boolean marked, Instant processedAt) {
        this.reservationParticipantId = reservationParticipantId;
        this.processedByMemberId = processedByMemberId;
        this.marked = marked;
        this.processedAt = processedAt;
    }

    public static NoShowHistory marked(Long reservationParticipantId, Long processedByMemberId, Instant processedAt) {
        return new NoShowHistory(reservationParticipantId, processedByMemberId, true, processedAt);
    }

    public static NoShowHistory unmarked(Long reservationParticipantId, Long processedByMemberId, Instant processedAt) {
        return new NoShowHistory(reservationParticipantId, processedByMemberId, false, processedAt);
    }

    public Long getId() {
        return id;
    }

    public Long getReservationParticipantId() {
        return reservationParticipantId;
    }

    public Long getProcessedByMemberId() {
        return processedByMemberId;
    }

    public boolean isMarked() {
        return marked;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
