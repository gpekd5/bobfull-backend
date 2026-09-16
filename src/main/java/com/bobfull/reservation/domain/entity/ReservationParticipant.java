package com.bobfull.reservation.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 한 회원의 예약 신청 인원과 참여·취소·노쇼 상태를 관리한다.
// 애플리케이션 검증을 동시에 통과한 중복 참여도 DB UNIQUE로 최종 차단한다.
@Entity
@Table(
        name = "reservation_participant",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_participant_member",
                        columnNames = {"reservation_id", "member_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationParticipant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_participant_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "party_size", nullable = false)
    private Integer partySize;

    @Enumerated(EnumType.STRING)
    @Column(name = "participation_status", nullable = false, length = 20)
    private ParticipationStatus participationStatus;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    private ReservationParticipant(Long reservationId, Long memberId, Integer partySize) {
        this.reservationId = reservationId;
        this.memberId = memberId;
        this.partySize = partySize;
        this.participationStatus = ParticipationStatus.RESERVED;
    }

    public static ReservationParticipant create(Long reservationId, Long memberId, Integer partySize) {
        return new ReservationParticipant(reservationId, memberId, partySize);
    }

    // 부분 취소 없이 참여 전체를 환불 대기로 전환하며, 환불 완료 전까지 좌석을 점유한다.
    public void requestCancel(String cancelReason) {
        this.participationStatus = ParticipationStatus.CANCEL_REQUESTED;
        this.cancelReason = cancelReason;
    }

    // 환불 완료를 취소 상태로 확정하며 중복 완료 요청은 멱등 처리한다.
    public void completeCancel(Instant cancelledAt) {
        if (participationStatus == ParticipationStatus.CANCELLED) {
            return;
        }
        if (participationStatus != ParticipationStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("CANCEL_REQUESTED 상태만 취소를 완료할 수 있습니다.");
        }
        this.participationStatus = ParticipationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    public boolean isCancellable() {
        return participationStatus == ParticipationStatus.RESERVED;
    }

    public void markNoShow() {
        if (participationStatus != ParticipationStatus.RESERVED) {
            throw new IllegalStateException("RESERVED 상태만 노쇼 처리할 수 있습니다.");
        }
        this.participationStatus = ParticipationStatus.NO_SHOW;
    }

    public void unmarkNoShow() {
        if (participationStatus != ParticipationStatus.NO_SHOW) {
            throw new IllegalStateException("NO_SHOW 상태만 노쇼 처리를 해제할 수 있습니다.");
        }
        this.participationStatus = ParticipationStatus.RESERVED;
    }

}
