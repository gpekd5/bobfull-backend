package com.bobfull.reservation.presentation.dto;

import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.reservation.application.dto.MyReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.OffsetDateTime;

public record MyReservationDetailResponse(
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
        String paymentId,
        PaymentStatus paymentStatus
) {
    public static MyReservationDetailResponse of(MyReservationResult result, OffsetDateTime startAt, OffsetDateTime endAt) {
        return new MyReservationDetailResponse(
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
                result.paymentId(),
                result.paymentStatus()
        );
    }
}
