package com.bobfull.chat.application.dto;

/** Provider 호출 결과와 관측 가능한 메타데이터다. 토큰 값은 제공되지 않으면 null이다. */
public record AiModerationResponse(
        ModerationResult result,
        String provider,
        String model,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens
) {
}
