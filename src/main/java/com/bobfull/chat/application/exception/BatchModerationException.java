package com.bobfull.chat.application.exception;

import java.util.List;
import java.util.Map;

// 필터링 전 poll index를 보존해 미완료 source offset을 건너뛰지 않게 한다.
public class BatchModerationException extends RuntimeException {
    private final int index;
    private final Map<Integer, RuntimeException> failures;
    private final List<List<Integer>> requestFailures;

    public BatchModerationException(int index, RuntimeException cause) {
        this(Map.of(index, cause), List.of());
    }

    public BatchModerationException(Map<Integer, RuntimeException> failures, List<List<Integer>> requestFailures) {
        super("MODERATION_BATCH_RECORD_FAILED", failures.get(failures.keySet().stream().min(Integer::compareTo).orElseThrow()));
        this.index = failures.keySet().stream().min(Integer::compareTo).orElseThrow();
        this.failures = Map.copyOf(failures);
        this.requestFailures = requestFailures.stream().map(List::copyOf).toList();
    }

    public int index() {
        return index;
    }

    public Map<Integer, RuntimeException> failures() {
        return failures;
    }

    public List<List<Integer>> requestFailures() {
        return requestFailures;
    }
}
