package com.bobfull.chat.application.service;

import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.exception.BatchProviderRequestException;
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
        execute(prepare(messageIds), false);
    }

    // Rule 저장까지 끝낸 준비 결과만 보관한다. Provider나 DB transaction을 수집 대기 동안 열어 두지 않는다.
    public List<PreparedItem> prepare(List<Long> messageIds) {
        List<PreparedItem> prepared = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : messageIds) {
            if (!seen.add(id)) {
                metrics.skipped();
                prepared.add(new PreparedItem(id, null, null, 0));
                continue;
            }
            try {
                var message = moderation.prepareBatch(id);
                if (message.isEmpty()) {
                    metrics.skipped();
                } else if (message.get().ruleHit()) {
                    metrics.processed("rule", message.get().createdAt(), clock.instant());
                }
                prepared.add(new PreparedItem(id, message.orElse(null), null, System.nanoTime()));
            } catch (RuntimeException exception) {
                prepared.add(new PreparedItem(id, null, exception, System.nanoTime()));
            }
        }
        return List.copyOf(prepared);
    }

    // 수집 뒤에는 완료 여부만 재확인한다. Rule/본문 조회를 반복하거나 수집 대기를 기존 처리 latency에 더하지 않는다.
    public void analyzePrepared(List<PreparedItem> prepared) {
        execute(prepared, true);
    }

    private void execute(List<PreparedItem> prepared, boolean deferred) {
        List<Target> targets = new ArrayList<>();
        Map<Integer, RuntimeException> failures = new LinkedHashMap<>();
        List<List<Integer>> requestFailures = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int index = 0; index < prepared.size(); index++) {
            PreparedItem item = prepared.get(index);
            if (item.failure != null) {
                failures.put(index, item.failure);
            } else if (item.requiresProvider() && seen.add(item.messageId)) {
                targets.add(new Target(index, item.message));
            } else if (item.requiresProvider()) {
                metrics.skipped();
            }
        }
        for (int start = 0; start < targets.size(); start += 10) {
            List<Target> group = targets.subList(start, Math.min(start + 10, targets.size()));
            if (deferred) {
                List<Target> pending = new ArrayList<>();
                for (Target target : group) {
                    try {
                        if (moderation.isCompleted(target.message().messageId())) {
                            metrics.skipped();
                        } else {
                            var item = prepared.get(target.index());
                            var message = target.message();
                            // 원래 준비 시간은 유지하고 준비 완료 이후의 새 대기시간만 제외한다.
                            long startedAt = message.startedAt() + (System.nanoTime() - item.preparedAt);
                            pending.add(new Target(target.index(), new ChatModerationService.PreparedMessage(
                                    message.messageId(), message.content(), message.createdAt(), startedAt, false)));
                        }
                    } catch (RuntimeException exception) {
                        failures.put(target.index(), exception);
                    }
                }
                group = pending;
            }
            if (group.isEmpty()) {
                continue;
            }
            if (group.size() == 1) {
                Target target = group.get(0);
                try {
                    moderation.analyzePreparedNative(target.message());
                    metrics.processed("native", target.message().createdAt(), clock.instant());
                } catch (RuntimeException exception) {
                    if (exception instanceof BatchProviderRequestException requestFailure) {
                        failures.put(target.index(), (RuntimeException) requestFailure.getCause());
                        requestFailures.add(List.of(target.index()));
                    } else {
                        failures.put(target.index(), exception);
                    }
                }
            } else {
                processBatch(group, failures, requestFailures);
            }
        }
        if (!failures.isEmpty()) {
            throw new BatchModerationException(failures, requestFailures);
        }
    }

    public boolean isCompleted(Long messageId) {
        return moderation.isCompleted(messageId);
    }

    private void processBatch(List<Target> targets, Map<Integer, RuntimeException> failures,
            List<List<Integer>> requestFailures) {
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
            requestFailures.add(targets.stream().map(Target::index).toList());
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

    // 준비 상태는 불변이며 Provider 재시도 때도 같은 본문/Rule 판정을 사용한다. 저장 전 완료 방어는 별도로 유지한다.
    public static final class PreparedItem {
        private final Long messageId;
        private final ChatModerationService.PreparedMessage message;
        private final RuntimeException failure;
        private final long preparedAt;

        private PreparedItem(Long messageId, ChatModerationService.PreparedMessage message,
                RuntimeException failure, long preparedAt) {
            this.messageId = messageId;
            this.message = message;
            this.failure = failure;
            this.preparedAt = preparedAt;
        }

        public Long messageId() {
            return messageId;
        }

        public boolean requiresProvider() {
            return message != null && !message.ruleHit();
        }

        public RuntimeException failure() {
            return failure;
        }
    }
}
