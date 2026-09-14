package com.bobfull.admin.presentation.dto;

import java.time.Instant;

/** 관리자 검토 대상 회원 목록의 원문 없는 집계 항목이다. */
public record AdminMemberModerationListItemResponse(
        Long memberId,
        long profanityCount,
        long personalInformationCount,
        long spamCount,
        long totalFlaggedCount,
        long reviewTargetCount,
        MemberModerationReviewStatus reviewStatus,
        Instant lastFlaggedAt
) {
}
