package com.bobfull.admin.application.result;

import java.time.Instant;

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
