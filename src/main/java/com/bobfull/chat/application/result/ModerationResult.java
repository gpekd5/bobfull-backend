package com.bobfull.chat.application.result;

import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.Set;

// 외부 AI가 반환해야 하는 Moderation 결과의 최소 구조다.
public record ModerationResult(
        ModerationResultType result,
        Set<ModerationCategory> categories,
        RiskLevel riskLevel
) {
}
