package com.bobfull.chat.application.dto;

import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.Set;

/** OpenAI가 반환하는 분석 결과의 최소 구조 계약이다. */
public record ModerationResult(
        ModerationResultType result,
        Set<ModerationCategory> categories,
        RiskLevel riskLevel
) {
}
