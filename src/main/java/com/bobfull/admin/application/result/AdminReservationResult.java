package com.bobfull.admin.application.result;

import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;

// 관리자 예약 현황 조회에 사용하는 결과다.
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
