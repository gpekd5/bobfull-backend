package com.bobfull.admin.application.model;

import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;

/** ADMIN 전체 예약 현황 조회 결과 1건이다(Issue #49 §11-5). */
public record AdminReservationResult(
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        Long creatorMemberId,
        Instant startAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        long currentParticipantCount,
        Integer capacity
) {
}
