package com.bobfull.reservation.presentation.response;

import com.bobfull.common.privacy.MemberNameMasker;
import com.bobfull.reservation.application.result.NoShowHistoryResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;

// 예약 참여자의 노쇼 처리 이력을 제공한다.
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
