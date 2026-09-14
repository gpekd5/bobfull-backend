package com.bobfull.chat.infrastructure.ai;

import org.springframework.ai.openai.OpenAiChatOptions;

/** Moderation 요청에만 적용하는 OpenAI runtime option 계약이다. */
final class ModerationOpenAiOptions {
    private ModerationOpenAiOptions() {
    }

    static OpenAiChatOptions.Builder withMaxOutputTokens(int maxOutputTokens) {
        return OpenAiChatOptions.builder().maxTokens(maxOutputTokens);
    }
}
