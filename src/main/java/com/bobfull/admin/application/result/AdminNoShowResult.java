package com.bobfull.admin.application.result;

import java.time.Instant;

// 관리자 노쇼 현황 조회에 사용하는 결과다.
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
