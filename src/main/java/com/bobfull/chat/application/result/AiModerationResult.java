package com.bobfull.chat.application.result;

// AI Provider 분석 결과와 관측 가능한 모델·토큰 메타데이터를 전달한다.
public record AiModerationResult(
        ModerationResult result,
        String provider,
        String model,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens
) {
}
