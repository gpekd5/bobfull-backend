package com.bobfull.reservation.application.result;

import java.time.Instant;

// 예약 참여자의 노쇼 처리 이력 한 건을 전달한다.
public record NoShowHistoryResult(
        Long noShowHistoryId,
        Long participationId,
        Long memberId,
        String memberName,
        Integer partySize,
        boolean marked,
        Long processedByMemberId,
        Instant processedAt
) {
}
