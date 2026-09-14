package com.bobfull.admin.presentation.dto;

import com.bobfull.admin.application.model.AdminNoShowResult;
import com.bobfull.common.privacy.MemberNameMasker;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** §11-8 전체 노쇼 현황 조회 응답이다(Issue #134). */
public record AdminNoShowListItemResponse(
        Long noShowHistoryId,
        Long memberId,
        String memberName,
        Long restaurantId,
        String restaurantName,
        Long reservationId,
        Long participationId,
        Integer partySize,
        OffsetDateTime processedAt
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static AdminNoShowListItemResponse of(AdminNoShowResult result) {
        return new AdminNoShowListItemResponse(
                result.noShowHistoryId(),
                result.memberId(),
                MemberNameMasker.mask(result.memberName()),
                result.restaurantId(),
                result.restaurantName(),
                result.reservationId(),
                result.participationId(),
                result.partySize(),
                OffsetDateTime.ofInstant(result.processedAt(), SEOUL));
    }
}
