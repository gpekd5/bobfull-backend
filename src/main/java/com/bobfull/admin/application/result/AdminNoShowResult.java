package com.bobfull.admin.application.result;

import java.time.Instant;

/** §11-8 전체 노쇼 현황 조회의 조회 결과 1건이다(Issue #134). */
public record AdminNoShowResult(
        Long noShowHistoryId,
        Long memberId,
        String memberName,
        Long restaurantId,
        String restaurantName,
        Long reservationId,
        Long participationId,
        Integer partySize,
        Instant processedAt
) {
}
