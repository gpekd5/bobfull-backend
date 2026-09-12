package com.bobfull.reservation.application.dto;

import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;

/** 로그인한 회원 본인의 참여(최초 예약자 포함) 기준으로 조회한 예약 1건이다. */
public record MyReservationResult(
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        Long sessionId,
        Instant startAt,
        Instant endAt,
        ReservationStatus reservationStatus,
        RecruitmentStatus recruitmentStatus,
        Long participationId,
        Integer partySize,
        ParticipationStatus participationStatus,
        PaymentStatus paymentStatus,
        String paymentId
) {
}
