package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModerationOpenAiOptionsTest {
    @Test
    void moderation_요청에만_maxTokens_128을_적용할_수_있다() {
        assertThat(ModerationOpenAiOptions.withMaxOutputTokens(128).build().getMaxTokens()).isEqualTo(128);
    }
}
