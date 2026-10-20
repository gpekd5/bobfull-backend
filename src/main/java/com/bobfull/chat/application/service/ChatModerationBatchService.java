package com.bobfull.chat.application.service;

import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.exception.ModerationAnalysisException;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// 한 poll의 Rule miss만 묶고 저장 실패를 원래 poll index로 되돌린다. 수집 대기열은 두지 않는다.
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bobfull.kafka.chat-message", name = "batch-enabled", havingValue = "true")
public class ChatModerationBatchService {
    private final ChatModerationService moderation;
    private final AiModerationBatchPort provider;
    private final ChatModerationBatchMetrics metrics;
    private final Clock clock;

    public void analyze(List<Long> messageIds) {
        List<Target> targets = new ArrayList<>();
        Map<Integer, RuntimeException> failures = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (int index = 0; index < messageIds.size(); index++) {
            Long id = messageIds.get(index);
            if (!seen.add(id)) {
                metrics.skipped();
                continue;
            }
            try {
                var prepared = moderation.prepareBatch(id);
                if (prepared.isEmpty()) {
                    metrics.skipped();
                } else if (prepared.get().ruleHit()) {
                    metrics.processed("rule", prepared.get().createdAt(), clock.instant());
                } else {
                    targets.add(new Target(index, prepared.get()));
                }
            } catch (RuntimeException exception) {
                failures.put(index, exception);
            }
        }
        for (int start = 0; start < targets.size(); start += 10) {
            List<Target> group = targets.subList(start, Math.min(start + 10, targets.size()));
            if (group.size() == 1) {
                Target target = group.get(0);
                try {
                    moderation.analyzePreparedNative(target.message());
                    metrics.processed("native", target.message().createdAt(), clock.instant());
                } catch (RuntimeException exception) {
                    failures.put(target.index(), exception);
                }
            } else {
                processBatch(group, failures);
            }
        }
        failures.entrySet().stream().min(Map.Entry.comparingByKey()).ifPresent(failure -> {
            throw new BatchModerationException(failure.getKey(), failure.getValue());
        });
    }

    private void processBatch(List<Target> targets, Map<Integer, RuntimeException> failures) {
        List<AiModerationBatchPort.Item> input = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            input.add(new AiModerationBatchPort.Item("m%03d".formatted(i + 1), targets.get(i).message().content()));
            moderation.recordBatchRoute();
        }
        metrics.batch(input.size());
        AiModerationBatchPort.BatchResult response;
        Map<String, AiModerationBatchPort.Output> mapped = new LinkedHashMap<>();
        try {
            response = provider.analyze(List.copyOf(input));
            if (response == null || response.results() == null || response.results().size() != input.size()) {
                throw new ModerationAnalysisException("MODERATION_BATCH_MAPPING");
            }
            for (var output : response.results()) {
                if (output == null || output.id() == null || mapped.putIfAbsent(output.id(), output) != null) {
                    throw new ModerationAnalysisException("MODERATION_BATCH_MAPPING");
                }
            }
            if (!mapped.keySet().equals(new HashSet<>(input.stream().map(AiModerationBatchPort.Item::id).toList()))) {
                throw new ModerationAnalysisException("MODERATION_BATCH_MAPPING");
            }
        } catch (RuntimeException exception) {
            metrics.failure("request_or_mapping");
            targets.forEach(target -> failures.put(target.index(), exception));
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            try {
                // Provider Usage는 요청 단위 자동 Metric에만 기록한다. 메시지별 Token은 알 수 없으므로 NULL.
                moderation.saveBatchResult(target.message(), new AiModerationResult(
                        mapped.get(input.get(i).id()).moderation(), response.provider(), response.model(), null, null, null));
                metrics.processed("batch", target.message().createdAt(), clock.instant());
            } catch (RuntimeException exception) {
                metrics.failure("validation_or_persistence");
                failures.put(target.index(), exception);
            }
        }
    }

    private record Target(int index, ChatModerationService.PreparedMessage message) {
    }
}
