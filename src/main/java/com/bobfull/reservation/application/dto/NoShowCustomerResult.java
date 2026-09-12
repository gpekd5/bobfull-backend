package com.bobfull.reservation.application.dto;

import java.time.Instant;

/** §9-5 식당 노쇼 고객 조회의 회원별 집계 결과 1건이다(Issue #48). */
public record NoShowCustomerResult(
        Long memberId,
        String memberName,
        long noShowCount,
        Instant latestNoShowAt,
        Long reservationId,
        Long participationId,
        Integer partySize
) {
}
