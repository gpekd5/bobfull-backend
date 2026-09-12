package com.bobfull.admin.dto;

import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.OffsetDateTime;

public record AdminReservationListItemResponse(
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        Long creatorMemberId,
        OffsetDateTime startAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        long currentParticipantCount,
        Integer capacity
) {
    public static AdminReservationListItemResponse of(AdminReservationResult result, OffsetDateTime startAt) {
        return new AdminReservationListItemResponse(
                result.reservationId(), result.restaurantId(), result.restaurantName(), result.creatorMemberId(),
                startAt, result.reservationStatus(), result.recruitmentStatus(),
                result.currentParticipantCount(), result.capacity());
    }
}
