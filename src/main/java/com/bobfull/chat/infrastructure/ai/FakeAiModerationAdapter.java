package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.application.port.AiModerationPort;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 외부 Provider 변동 없이 지연·결과·실패를 재현하는 Moderation 측정용 Adapter다.
@Component
@ConditionalOnProperty(prefix = "bobfull.ai.moderation", name = "fake-enabled", havingValue = "true")
public class FakeAiModerationAdapter implements AiModerationPort {
    // FLAGGED 결과가 내부 조합 규칙을 만족하도록 고정 category를 채운다.
    private static final Set<ModerationCategory> DEFAULT_FLAGGED_CATEGORIES = Set.of(ModerationCategory.PROFANITY);
    // 특정 측정 메시지만 실패시키도록 운영 콘텐츠와 충돌하지 않는 고유 문자열을 사용한다.
    public static final String FORCE_FAIL_MARKER = "FAKE_AI_FORCE_FAIL";

    private final long latencyMs;
    private final ModerationResultType resultType;

    public FakeAiModerationAdapter(
            @Value("${bobfull.ai.moderation.fake-latency-ms:0}") long latencyMs,
            @Value("${bobfull.ai.moderation.fake-result-type:SAFE}") ModerationResultType resultType) {
        this.latencyMs = latencyMs;
        this.resultType = resultType;
    }

    @Override
    public AiModerationResult analyze(String content) {
        simulateLatency();
        if (content != null && content.contains(FORCE_FAIL_MARKER)) {
            throw new IllegalStateException("Fake AI 강제 실패(실험 C 격리 테스트)");
        }
        Set<ModerationCategory> categories = resultType == ModerationResultType.SAFE
                ? Set.of() : DEFAULT_FLAGGED_CATEGORIES;
        RiskLevel riskLevel = resultType == ModerationResultType.SAFE ? RiskLevel.LOW : RiskLevel.HIGH;
        return new AiModerationResult(new ModerationResult(resultType, categories, riskLevel),
                "Fake", "fake-model", 0L, 0L, 0L);
    }

    private void simulateLatency() {
        if (latencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fake AI 지연 시뮬레이션이 중단됐습니다.", e);
        }
    }
}
