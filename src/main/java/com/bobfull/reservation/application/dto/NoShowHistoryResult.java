package com.bobfull.reservation.application.dto;

import java.time.Instant;

/** §9-4 예약별 노쇼 이력 조회의 조회 결과 1건이다(Issue #48). */
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
