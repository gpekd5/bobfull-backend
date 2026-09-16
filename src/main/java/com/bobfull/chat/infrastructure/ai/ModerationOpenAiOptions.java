package com.bobfull.chat.infrastructure.ai;

import org.springframework.ai.openai.OpenAiChatOptions;

// Moderation 호출에만 적용할 OpenAI runtime option을 구성한다.
final class ModerationOpenAiOptions {
    private ModerationOpenAiOptions() {
    }

    static OpenAiChatOptions.Builder withMaxOutputTokens(int maxOutputTokens) {
        return OpenAiChatOptions.builder().maxTokens(maxOutputTokens);
    }
}
