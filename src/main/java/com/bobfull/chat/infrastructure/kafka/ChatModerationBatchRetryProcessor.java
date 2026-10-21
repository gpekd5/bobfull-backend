package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.KafkaException;

// 상태는 Consumer thread/현재 할당에만 속한다. 영속 호출 상한이나 exactly-once를 제공하지 않는다.
public class ChatModerationBatchRetryProcessor {
    private final ChatModerationBatchService service;
    private final ChatModerationDltRecoverer recoverer;
    private final int maxAttempts;
    private final ThreadLocal<Map<RecordKey, Failure>> states = ThreadLocal.withInitial(LinkedHashMap::new);

    public ChatModerationBatchRetryProcessor(ChatModerationBatchService service, ChatModerationDltRecoverer recoverer,
            int maxAttempts) {
        this.service = service;
        this.recoverer = recoverer;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public void pruneCommitted(Consumer<?, ?> consumer) {
        Map<RecordKey, Failure> state = states.get();
        if (state.isEmpty()) {
            return;
        }
        var committed = consumer.committed(state.keySet().stream().map(RecordKey::partition).collect(Collectors.toSet()));
        state.keySet().removeIf(key -> committed.get(key.partition()) != null
                && committed.get(key.partition()).offset() > key.offset());
    }

    public boolean isRedelivery(ConsumerRecord<?, ?> record) {
        return states.get().containsKey(RecordKey.of(record));
    }

    // indexed Retry의 횟수/commit은 framework에 맡기고, recovery에 진입한 사실만 보존한다.
    public void recoverIndexed(ConsumerRecord<?, ?> record, Exception exception) {
        if (!(record.value() instanceof ChatMessageCreatedEvent event) || event.messageId() == null) {
            recoverer.accept(record, exception);
            return;
        }
        RuntimeException cause = exception instanceof RuntimeException runtime
                ? runtime : new KafkaException("MODERATION_RECOVERY_FAILED", exception);
        Failure state = states.get().computeIfAbsent(RecordKey.of(record), key -> new Failure(new Object(), 0, cause));
        state.phase = Phase.RECOVERY;
        recoverer.accept(record, exception);
        state.phase = Phase.RECOVERED;
    }

    public void analyze(List<ConsumerRecord<Object, Object>> records) {
        analyze(records, service::analyze);
    }

    // 실행 경계만 교체한다. Retry 그룹/예산/복구는 기존과 같은 Consumer thread 상태를 사용한다.
    public void analyze(List<ConsumerRecord<Object, Object>> records,
            java.util.function.Consumer<List<Long>> execution) {
        Map<Integer, RuntimeException> failures = new HashMap<>();
        Map<Object, List<Integer>> groups = new LinkedHashMap<>();
        Object fresh = new Object();
        for (int index = 0; index < records.size(); index++) {
            var record = records.get(index);
            Failure state = states.get().get(RecordKey.of(record));
            if (state != null && state.phase != Phase.RETRY) {
                recover(record, index, state, failures);
            } else {
                groups.computeIfAbsent(state == null ? fresh : state.group, unused -> new ArrayList<>()).add(index);
            }
        }
        for (var indexes : groups.values()) {
            try {
                execution.accept(indexes.stream().map(index -> messageId(records.get(index))).toList());
                indexes.forEach(index -> states.get().remove(RecordKey.of(records.get(index))));
            } catch (BatchModerationException exception) {
                Set<Integer> requestIndexes = exception.requestFailures().stream().flatMap(List::stream).collect(Collectors.toSet());
                for (int local = 0; local < indexes.size(); local++) {
                    if (!requestIndexes.contains(local)) {
                        states.get().remove(RecordKey.of(records.get(indexes.get(local))));
                    }
                }
                exception.failures().forEach((local, cause) -> {
                    if (!requestIndexes.contains(local)) {
                        failures.put(indexes.get(local), cause);
                    }
                });
                for (var request : exception.requestFailures()) {
                    Object lineage = new Object();
                    for (int local : request) {
                        int index = indexes.get(local);
                        var record = records.get(index);
                        var key = RecordKey.of(record);
                        Failure old = states.get().get(key);
                        Failure state = new Failure(lineage, old == null ? 1 : old.attempts + 1,
                                exception.failures().get(local));
                        states.get().put(key, state);
                        if (state.attempts >= maxAttempts) {
                            state.phase = Phase.RECOVERY;
                            recover(record, index, state, failures);
                        } else {
                            failures.put(index, new GroupRetryException(index, state.cause));
                        }
                    }
                }
            }
        }
        failures.keySet().stream().min(Integer::compareTo).ifPresent(index -> {
            RuntimeException failure = failures.get(index);
            if (failure instanceof GroupRetryException groupFailure) {
                throw groupFailure;
            }
            throw new BatchListenerFailedException("MODERATION_BATCH_RECORD_FAILED", failure, index);
        });
    }

    private void recover(ConsumerRecord<Object, Object> record, int index, Failure state,
            Map<Integer, RuntimeException> failures) {
        if (state.phase == Phase.RECOVERED) {
            return;
        }
        try {
            if (!service.isCompleted(messageId(record))) {
                recoverer.accept(record, state.cause);
            }
            state.phase = Phase.RECOVERED;
        } catch (RuntimeException failure) {
            state.phase = Phase.RECOVERY;
            failures.put(index, new GroupRetryException(index, failure));
        }
    }

    public void revoke(Collection<TopicPartition> partitions) {
        states.get().keySet().removeIf(key -> partitions.contains(key.partition()));
    }

    public void clearThreadState() {
        states.remove();
    }

    private static Long messageId(ConsumerRecord<?, ?> record) {
        return ((ChatMessageCreatedEvent) record.value()).messageId();
    }

    static final class GroupRetryException extends BatchListenerFailedException {
        GroupRetryException(int index, RuntimeException cause) {
            super("MODERATION_BATCH_GROUP_PENDING", cause, index);
        }
    }

    private enum Phase { RETRY, RECOVERY, RECOVERED }

    private static final class Failure {
        final Object group;
        final int attempts;
        final RuntimeException cause;
        Phase phase = Phase.RETRY;

        Failure(Object group, int attempts, RuntimeException cause) {
            this.group = group;
            this.attempts = attempts;
            this.cause = cause;
        }
    }

    private record RecordKey(TopicPartition partition, long offset) {
        static RecordKey of(ConsumerRecord<?, ?> record) {
            return new RecordKey(new TopicPartition(record.topic(), record.partition()), record.offset());
        }
    }
}
