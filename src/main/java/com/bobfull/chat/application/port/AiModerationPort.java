package com.bobfull.chat.application.port;

import com.bobfull.chat.application.result.AiModerationResult;

// Moderation 흐름이 외부 AI Provider SDK에 의존하지 않도록 하는 분석 경계다.
public interface AiModerationPort {
    AiModerationResult analyze(String content);
}
