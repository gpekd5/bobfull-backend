package com.bobfull.chat.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// poll-batch의 충전율과 메시지 완료 지연을 기록하며 계측 실패는 업무에 전파하지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModerationBatchMetrics {
    private final MeterRegistry registry;

    public void poll(int size) {
        safely(() -> registry.summary("bobfull.ai.moderation.poll.size").record(size));
    }

    public void batch(int size) {
        safely(() -> {
            registry.summary("bobfull.ai.moderation.batch.size").record(size);
            registry.summary("bobfull.ai.moderation.batch.fill.ratio").record(size / 10.0);
        });
    }

    public void processed(String path, Instant createdAt, Instant completedAt) {
        safely(() -> {
            registry.counter("bobfull.ai.moderation.processing", "path", path).increment();
            if (createdAt != null && !completedAt.isBefore(createdAt)) {
                Timer.builder("bobfull.ai.moderation.e2e").tag("path", path).publishPercentileHistogram().register(registry)
                        .record(Duration.between(createdAt, completedAt));
            }
        });
    }

    public void skipped() {
        safely(() -> registry.counter("bobfull.ai.moderation.processing", "path", "skipped").increment());
    }

    public void failure(String stage) {
        safely(() -> registry.counter("bobfull.ai.moderation.batch.failure", "stage", stage).increment());
    }

    public void estimatedCost(long input, Long cached, long output) {
        // 공개 GPT-4o-mini Standard 단가 기반 추정. 알 수 없는 Usage는 0으로 만들지 않는다.
        safely(() -> {
            registry.counter("bobfull.ai.moderation.batch.estimated.cost.usd", "basis", "uncached")
                    .increment((input * 0.15 + output * 0.60) / 1_000_000);
            if (cached != null && cached >= 0 && cached <= input) {
                registry.counter("bobfull.ai.moderation.batch.cached.input.tokens").increment(cached);
                registry.counter("bobfull.ai.moderation.batch.estimated.cost.usd", "basis", "cache_adjusted")
                        .increment(((input - cached) * 0.15 + cached * 0.075 + output * 0.60) / 1_000_000);
            }
        });
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("event=CHAT_MODERATION_BATCH_METRIC_FAILED type={}", exception.getClass().getSimpleName());
        }
    }
}
