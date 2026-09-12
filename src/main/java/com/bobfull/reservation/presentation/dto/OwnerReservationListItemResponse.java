package com.bobfull.reservation.presentation.dto;

import com.bobfull.reservation.application.dto.OwnerReservationResult;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.OffsetDateTime;

/** §6-11 식당별 예약 목록 조회 응답이다(Issue #147). */
public record OwnerReservationListItemResponse(
        Long reservationId,
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
    public static OwnerReservationListItemResponse of(
            OwnerReservationResult result,
            OffsetDateTime startAt,
            OffsetDateTime endAt
    ) {
        return new OwnerReservationListItemResponse(
                result.reservationId(),
                result.sessionId(),
                result.tableId(),
                result.capacity(),
                startAt,
                endAt,
                result.reservationStatus(),
                result.recruitmentStatus(),
                Math.toIntExact(result.currentParticipantCount()),
                result.availableCapacity(),
                result.confirmationThreshold()
        );
    }
}
