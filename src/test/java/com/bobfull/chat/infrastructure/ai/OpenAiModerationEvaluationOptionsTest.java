package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAiModerationEvaluationOptionsTest {
    @Test
    void gpt_5_4_nano는_max_completion_tokens와_reasoning_none을_사용한다() {
        var options = OpenAiModerationEvaluationOptions.forModel("gpt-5.4-nano", 128).build();

        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getMaxCompletionTokens()).isEqualTo(128);
        assertThat(options.getReasoningEffort()).isEqualTo("none");
    }
}
