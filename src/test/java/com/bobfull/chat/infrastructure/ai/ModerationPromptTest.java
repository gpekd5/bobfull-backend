package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ModerationPromptTest {
    @Test
    void Scope_조정된_prompt와_policy_버전_및_명백한_위반_경계를_관리한다() {
        assertThat(ModerationPrompt.PROMPT_VERSION).isEqualTo("moderation-prompt-v3-short-fragment-boundary");
        assertThat(ModerationPrompt.POLICY_VERSION).isEqualTo("moderation-policy-v2");
        assertThat(ModerationPrompt.SYSTEM_PROMPT)
                .contains("애매한 표현은 FLAGGED로 추정하지 말고 SAFE")
                .contains("\"바보야\" → SAFE")
                .contains("직접 연락·식별 정보")
                .contains("링크 자체만으로 SPAM 처리하지 않는다")
                .contains("\"개새끼야\" → FLAGGED")
                .contains("\"내 번호 010-1234-5678이야\" → FLAGGED")
                .contains("\"코인 수익방 들어오세요 https://example.com\" → FLAGGED");
    }
}
