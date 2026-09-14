package com.bobfull.admin.application.model;

import java.time.Instant;

/** DB 집계 결과를 service가 API 응답으로 변환하기 위한 내부 조회 모델이다. */
public record MemberModerationSummaryResult(
        Long memberId,
        long profanityCount,
        long personalInformationCount,
        long spamCount,
        long totalFlaggedCount,
        long reviewTargetCount,
        Instant lastFlaggedAt
) {
}
