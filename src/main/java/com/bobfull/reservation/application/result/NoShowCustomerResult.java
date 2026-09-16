package com.bobfull.reservation.application.result;

import java.time.Instant;

// 식당 노쇼 고객의 회원별 집계 결과를 전달한다.
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
