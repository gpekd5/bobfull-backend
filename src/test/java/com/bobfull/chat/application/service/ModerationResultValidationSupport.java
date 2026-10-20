package com.bobfull.chat.application.service;

import com.bobfull.chat.application.result.ModerationResult;

// Production Validator의 가시성을 바꾸지 않고 독립 평가에서 같은 조합 검증을 사용한다.
public final class ModerationResultValidationSupport {
    private ModerationResultValidationSupport() {
    }

    public static void validate(ModerationResult result) {
        ModerationResultValidator.validate(result);
    }
}
