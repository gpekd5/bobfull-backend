package com.bobfull.reservation.application.result;

import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
import java.time.Instant;

// 식당별 예약 목록 조회 결과 한 건을 전달한다.
public record OwnerReservationResult(
        Long reservationId,
        Long sessionId,
        Long tableId,
        Integer capacity,
        Instant startAt,
        Instant endAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        Long currentParticipantCount,
        Long temporaryHeldCount
) {
    public int availableCapacity() {
        return ReservationCapacityPolicy.availableCapacity(capacity, currentParticipantCount, temporaryHeldCount);
    }

    public int confirmationThreshold() {
        return ReservationCapacityPolicy.confirmationThreshold(capacity);
    }
}
