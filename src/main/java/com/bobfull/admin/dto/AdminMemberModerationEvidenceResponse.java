package com.bobfull.admin.dto;

import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.time.Instant;
import java.util.Set;

/** 회원 상세에서만 노출하는 FLAGGED 메시지의 관리자 검토 근거다. */
public record AdminMemberModerationEvidenceResponse(
        Long messageId,
        String content,
        Set<ModerationCategory> categories,
        RiskLevel riskLevel,
        boolean countedForReview,
        Instant sentAt,
        Instant analyzedAt
) {
}
