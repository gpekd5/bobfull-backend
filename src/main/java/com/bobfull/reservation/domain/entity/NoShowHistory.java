package com.bobfull.reservation.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 참여자 단위 노쇼 처리와 해제 사실을 순서대로 기록한다.
@Entity
@Table(name = "no_show_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

}
