package com.bobfull.chat.application.port;

import com.bobfull.chat.application.result.AiModerationResult;

/** ChatModerationService가 Provider SDK에 의존하지 않도록 하는 AI 분석 경계다. */
public interface AiModerationPort {
    AiModerationResult analyze(String content);
}
