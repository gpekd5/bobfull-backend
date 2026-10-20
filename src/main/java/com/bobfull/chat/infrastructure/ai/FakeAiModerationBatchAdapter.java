package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

// 단건 Fake를 N회 호출하지 않고 한 요청의 지연·실패와 항목별 결과를 분리한다.
@Component
@ConditionalOnProperty(prefix = "bobfull.kafka.chat-message", name = "batch-enabled", havingValue = "true")
@ConditionalOnExpression("${bobfull.ai.moderation.fake-enabled:false}")
public class FakeAiModerationBatchAdapter implements AiModerationBatchPort {
    private final long latencyMs;
    private final ModerationResultType resultType;

    public FakeAiModerationBatchAdapter(
            @Value("${bobfull.ai.moderation.fake-latency-ms:0}") long latencyMs,
            @Value("${bobfull.ai.moderation.fake-result-type:SAFE}") ModerationResultType resultType) {
        this.latencyMs = latencyMs;
        this.resultType = resultType;
    }

    @Override
    public BatchResult analyze(List<Item> items) {
        if (items.size() < 2 || items.size() > 10) {
            throw new IllegalArgumentException("Batch size must be between 2 and 10");
        }
        simulateLatency();
        if (items.stream().anyMatch(item -> item.content() != null
                && item.content().contains(FakeAiModerationAdapter.FORCE_FAIL_MARKER))) {
            throw new IllegalStateException("Fake AI 강제 실패(실험 C 격리 테스트)");
        }
        Set<ModerationCategory> categories = resultType == ModerationResultType.SAFE
                ? Set.of() : Set.of(ModerationCategory.PROFANITY);
        RiskLevel riskLevel = resultType == ModerationResultType.SAFE ? RiskLevel.LOW : RiskLevel.HIGH;
        ModerationResult result = new ModerationResult(resultType, categories, riskLevel);
        return new BatchResult(items.stream().map(item -> new Output(item.id(), result)).toList(),
                "Fake", "fake-model");
    }

    private void simulateLatency() {
        if (latencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fake AI 지연 시뮬레이션이 중단됐습니다.", exception);
        }
    }
}
