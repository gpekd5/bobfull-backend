package com.bobfull.chat.application.service;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.exception.ModerationAnalysisException;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;

/** Structured Output도 외부 입력이므로 BobFull 조합 규칙을 다시 검증한다. */
final class ModerationResultValidator {
    private ModerationResultValidator() {
    }
    static void validate(ModerationResult result) {
        if (result == null || result.result() == null || result.categories() == null || result.riskLevel() == null) {
            throw new ModerationAnalysisException("MODERATION_RESULT_MISSING_FIELD");
        }
        if (result.result() == ModerationResultType.SAFE
                && (!result.categories().isEmpty() || result.riskLevel() != RiskLevel.LOW)) {
            throw new ModerationAnalysisException("MODERATION_RESULT_SAFE_CONFLICT");
        }
        if (result.result() == ModerationResultType.FLAGGED && result.categories().isEmpty()) {
            throw new ModerationAnalysisException("MODERATION_RESULT_FLAGGED_CATEGORY_MISSING");
        }
    }
}
