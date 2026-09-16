package com.bobfull.reservation.presentation.response;

import com.bobfull.reservation.application.result.OwnerReservationResult;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.OffsetDateTime;

// 식당별 예약 목록의 한 항목을 제공한다.
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
