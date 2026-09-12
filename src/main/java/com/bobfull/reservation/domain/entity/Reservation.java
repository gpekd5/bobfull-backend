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

/**
 * 하나의 TimeSlot에 대한 합석 예약이다(docs/030-data/erd.md 4.5).
 * 결제 검증 전에는 생성하지 않으며(ADR 0001), 취소 이력 보존을 위해 CANCELLED 상태도
 * TimeSlot 연결을 유지한다.
 */
@Entity
@Table(name = "reservation", indexes = @jakarta.persistence.Index(name = "idx_reservation_time_slot_id", columnList = "time_slot_id"))
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @Column(name = "time_slot_id", nullable = false)
    private Long timeSlotId;

    @Column(name = "creator_member_id", nullable = false)
    private Long creatorMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status", nullable = false, length = 20)
    private ReservationStatus reservationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "recruitment_status", nullable = false, length = 20)
    private RecruitmentStatus recruitmentStatus;

    protected Reservation() {
    }

    private Reservation(Long timeSlotId, Long creatorMemberId) {
        this.timeSlotId = timeSlotId;
        this.creatorMemberId = creatorMemberId;
        this.reservationStatus = ReservationStatus.RECRUITING;
        this.recruitmentStatus = RecruitmentStatus.OPEN;
    }

    public static Reservation create(Long timeSlotId, Long creatorMemberId) {
        return new Reservation(timeSlotId, creatorMemberId);
    }

    /**
     * 확정 기준(docs/020-api/bobfull-api-spec-complete.md §0.8) 도달 시 RECRUITING에서 CONFIRMED로 전이한다.
     */
    public void confirm() {
        if (reservationStatus == ReservationStatus.RECRUITING) {
            this.reservationStatus = ReservationStatus.CONFIRMED;
        }
    }

    /**
     * 정원 도달 시 추가 참여 모집을 마감한다(§0.8).
     */
    public void closeRecruitment() {
        this.recruitmentStatus = RecruitmentStatus.CLOSED;
    }

    /**
     * 취소 접수 트랜잭션에서 예약 전체 취소를 시작한다(Issue #44). 환불이 실제로 완료되기 전까지는
     * {@link ReservationStatus#CANCELLING}으로 남아 좌석·활성 예약 조회에서 계속 점유 상태로 집계되며,
     * 환불 완료 후 {@link #cancel()}로 확정된다.
     */
    public void startCancelling() {
        this.reservationStatus = ReservationStatus.CANCELLING;
    }

    /**
     * 취소 접수(CANCELLING)로 시작된 모든 참여자의 환불이 완료되어 예약 전체 취소를 확정한다
     * (Issue #44 완료 경로, {@code ReservationCancellationCompletionService}가 호출, V2, #45/PR #144).
     * TimeSlot 복구는 별도 상태 컬럼이 아니라 이 상태 전이만으로 파생된다 — 활성 Reservation
     * 조회(`existsByTimeSlotIdAndReservationStatusIn`)가 곧바로 false가 된다.
     */
    public void cancel() {
        this.reservationStatus = ReservationStatus.CANCELLED;
    }

    public boolean isCancelled() {
        return reservationStatus == ReservationStatus.CANCELLED;
    }

    public boolean isCancelling() {
        return reservationStatus == ReservationStatus.CANCELLING;
}
    /**
     * 추가 참여자 취소로 확정 기준 미달이 되면 모집이 OPEN인 동안 CONFIRMED에서 RECRUITING으로
     * 되돌린다(Issue #131). 이미 RECRUITING이면 그대로 둔다.
     */
    public void revertToRecruiting() {
        if (reservationStatus == ReservationStatus.CONFIRMED) {
            this.reservationStatus = ReservationStatus.RECRUITING;
        }
    }

    public boolean isActive() {
        return reservationStatus == ReservationStatus.RECRUITING || reservationStatus == ReservationStatus.CONFIRMED;
    }

    /**
     * 식사 종료(TimeSlot.endAt 도달) 후보를 스케줄러 한 건씩 처리할 때 호출한다(Issue #175).
     * {@code CONFIRMED}에서만 {@code CLOSED}로 전이하고, 이미 {@code CLOSED}이거나
     * {@code RECRUITING}·{@code CANCELLING}·{@code CANCELLED}면 아무 것도 바꾸지 않아 같은 후보가
     * 여러 스케줄 주기에 걸쳐 조회돼도 중복 반영되지 않는다.
     */
    public void close() {
        if (reservationStatus == ReservationStatus.CONFIRMED) {
            this.reservationStatus = ReservationStatus.CLOSED;
        }
    }

    public boolean isClosed() {
        return reservationStatus == ReservationStatus.CLOSED;
    }

    public boolean isCreatedBy(Long memberId) {
        return this.creatorMemberId.equals(memberId);
    }

    public Long getId() {
        return id;
    }

    public Long getTimeSlotId() {
        return timeSlotId;
    }

    public Long getCreatorMemberId() {
        return creatorMemberId;
    }

    public ReservationStatus getReservationStatus() {
        return reservationStatus;
    }

    public RecruitmentStatus getRecruitmentStatus() {
        return recruitmentStatus;
    }
}
