package com.bobfull.admin.presentation.response;

import com.bobfull.admin.application.model.MemberModerationReviewStatus;
import java.util.List;
import java.util.Map;

/** 회원별 moderation 집계와 FLAGGED 근거 메시지를 함께 제공하는 관리자 상세 응답이다. */
public record AdminMemberModerationDetailResponse(
        Long memberId,
        MemberModerationReviewStatus reviewStatus,
        long totalFlaggedCount,
        long reviewTargetCount,
        Map<String, Long> riskCounts,
        List<AdminMemberModerationEvidenceResponse> evidences
) {
}
