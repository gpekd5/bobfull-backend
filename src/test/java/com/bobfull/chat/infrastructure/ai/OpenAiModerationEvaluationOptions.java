package com.bobfull.chat.infrastructure.ai;

import org.springframework.ai.openai.OpenAiChatOptions;

/** 모델 API 호환성을 확인하는 opt-in Evaluation 전용 request option이다. */
final class OpenAiModerationEvaluationOptions {
    private static final String GPT_5_4_NANO = "gpt-5.4-nano";

    private OpenAiModerationEvaluationOptions() {
    }

    static OpenAiChatOptions.Builder forModel(String model, int maxOutputTokens) {
        if (GPT_5_4_NANO.equals(model)) {
            return OpenAiChatOptions.builder()
                    .maxCompletionTokens(maxOutputTokens)
                    .reasoningEffort("none");
        }
        return ModerationOpenAiOptions.withMaxOutputTokens(maxOutputTokens);
    }

    static String outputTokenOptionName(String model) {
        return GPT_5_4_NANO.equals(model) ? "maxCompletionTokens" : "maxTokens";
    }
}
