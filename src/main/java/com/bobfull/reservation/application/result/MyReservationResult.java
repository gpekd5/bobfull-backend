package com.bobfull.reservation.application.result;

import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;

// 로그인 회원의 참여 관계를 기준으로 조회한 예약 한 건을 전달한다.
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
