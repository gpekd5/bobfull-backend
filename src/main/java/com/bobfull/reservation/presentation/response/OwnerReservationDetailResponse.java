package com.bobfull.reservation.presentation.response;

import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.OffsetDateTime;

/** §6-12 사장님용 예약 상세 조회 응답이다(Issue #147). */
public record OwnerReservationDetailResponse(
        Long reservationId,
        Long restaurantId,
        Long sessionId,
        Long tableId,
        Integer capacity,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        Integer currentParticipantCount,
        Integer availableCapacity,
        Integer confirmationThreshold
) {
}
