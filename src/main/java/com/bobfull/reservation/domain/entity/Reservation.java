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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 결제 완료 뒤 생성되어 한 회차의 모집과 성사·취소 생명주기를 관리한다.
@Entity
@Table(name = "reservation", indexes = @jakarta.persistence.Index(name = "idx_reservation_time_slot_id", columnList = "time_slot_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @Column(name = "time_slot_id", nullable = false)
    // 취소 뒤에도 이력과 후속 조회가 이어지도록 회차 연결을 유지한다.
    private Long timeSlotId;

    @Column(name = "creator_member_id", nullable = false)
    private Long creatorMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status", nullable = false, length = 20)
    private ReservationStatus reservationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "recruitment_status", nullable = false, length = 20)
    private RecruitmentStatus recruitmentStatus;

    private Reservation(Long timeSlotId, Long creatorMemberId) {
        this.timeSlotId = timeSlotId;
        this.creatorMemberId = creatorMemberId;
        this.reservationStatus = ReservationStatus.RECRUITING;
        this.recruitmentStatus = RecruitmentStatus.OPEN;
    }

    public static Reservation create(Long timeSlotId, Long creatorMemberId) {
        return new Reservation(timeSlotId, creatorMemberId);
    }

    // 최소 성사 인원에 도달한 모집 중 예약을 확정한다.
    public void confirm() {
        if (reservationStatus == ReservationStatus.RECRUITING) {
            this.reservationStatus = ReservationStatus.CONFIRMED;
        }
    }

    // 정원 도달 또는 모집 기한 만료로 추가 참여를 마감한다.
    public void closeRecruitment() {
        this.recruitmentStatus = RecruitmentStatus.CLOSED;
    }

    // 환불 완료 전까지 좌석을 계속 점유하도록 예약 전체 취소를 CANCELLING으로 접수한다.
    public void startCancelling() {
        this.reservationStatus = ReservationStatus.CANCELLING;
    }

    // 모든 참여자의 환불 완료 뒤 전체 취소를 확정해 회차 좌석을 다시 사용할 수 있게 한다.
    public void cancel() {
        this.reservationStatus = ReservationStatus.CANCELLED;
    }

    public boolean isCancelled() {
        return reservationStatus == ReservationStatus.CANCELLED;
    }

    public boolean isCancelling() {
        return reservationStatus == ReservationStatus.CANCELLING;
    }

    // 추가 참여 취소로 성사 기준에 미달하면 모집 중인 예약으로 되돌린다.
    public void revertToRecruiting() {
        if (reservationStatus == ReservationStatus.CONFIRMED) {
            this.reservationStatus = ReservationStatus.RECRUITING;
        }
    }

    public boolean isActive() {
        return reservationStatus == ReservationStatus.RECRUITING || reservationStatus == ReservationStatus.CONFIRMED;
    }

    // 식사가 끝난 확정 예약만 CLOSED로 전이해 반복 처리에도 멱등성을 유지한다.
    public void close() {
        if (reservationStatus == ReservationStatus.CONFIRMED) {
            this.reservationStatus = ReservationStatus.CLOSED;
        }
    }

    public boolean isClosed() {
        return reservationStatus == ReservationStatus.CLOSED;
    }

    public boolean isCreatedBy(Long memberId) {
        // 최초 참여자는 별도 역할 컬럼 없이 예약 생성자 식별자로 판별한다.
        return this.creatorMemberId.equals(memberId);
    }

}
