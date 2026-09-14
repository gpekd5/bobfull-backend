package com.bobfull.reservation.presentation.dto;

import com.bobfull.common.privacy.MemberNameMasker;
import com.bobfull.reservation.application.dto.NoShowHistoryResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** §9-4 예약별 노쇼 이력 조회 응답이다(Issue #48). */
public record NoShowHistoryResponse(
        Long noShowHistoryId,
        Long participationId,
        Long memberId,
        String name,
        Integer partySize,
        boolean isMarked,
        Long processedByMemberId,
        OffsetDateTime processedAt
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static NoShowHistoryResponse of(NoShowHistoryResult result) {
        return new NoShowHistoryResponse(
                result.noShowHistoryId(),
                result.participationId(),
                result.memberId(),
                MemberNameMasker.mask(result.memberName()),
                result.partySize(),
                result.marked(),
                result.processedByMemberId(),
                OffsetDateTime.ofInstant(result.processedAt(), SEOUL));
    }
}
