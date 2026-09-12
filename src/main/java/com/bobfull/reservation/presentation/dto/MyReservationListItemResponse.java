package com.bobfull.reservation.presentation.dto;

import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.reservation.application.dto.MyReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.OffsetDateTime;

public record MyReservationListItemResponse(
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        Long sessionId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        Long participationId,
        Integer partySize,
        ParticipationStatus participationStatus,
        PaymentStatus paymentStatus
) {
    public static MyReservationListItemResponse of(MyReservationResult result, OffsetDateTime startAt, OffsetDateTime endAt) {
        return new MyReservationListItemResponse(
                result.reservationId(),
                result.restaurantId(),
                result.restaurantName(),
                result.sessionId(),
                startAt,
                endAt,
                result.reservationStatus(),
                result.recruitmentStatus(),
                result.participationId(),
                result.partySize(),
                result.participationStatus(),
                result.paymentStatus()
        );
    }
}
