package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Batch;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// B 크기 탐색 전용. F0의 20건을 그대로 분할하며 순서/구성 변경 실험은 포함하지 않는다.
final class Issue55MicroBatchSizeComparison {
    static final double PRIOR_ESTIMATED_USD = .10352240;
    static final List<Integer> SIZES = List.of(2, 5, 10, 20);
    record Step(int repeat, int batchSize, int batchIndex) { }
    record Call(Step step, String requestSpecSha256, String startedAt, long processingNanos,
                int httpAttempts, BatchObservation response) { }
    record Group(int repeat, int batchSize, Issue55MicroBatchF0.Stats stats) { }
    record Series(String caseId, int batchSize, ModerationResult expected,
                  List<ModerationResult> results, boolean changed) { }
    record Comparison(int repeat, int referenceSize, int comparedSize,
                      List<Issue55MicroBatchComparison.Change> changes, List<String> gtHighDowngradeIds) { }

    static List<Batch> batches(int size) {
        if (!SIZES.contains(size)) {
            throw new IllegalArgumentException("B supports only 2/5/10/20");
        }
        var targets = Issue55MicroBatchF0.targets();
        List<Batch> result = new ArrayList<>();
        for (int i = 0; i < targets.size(); i += size) {
            result.add(new Batch("size-b%02d-%02d".formatted(size, result.size() + 1),
                    targets.subList(i, Math.min(i + size, targets.size()))));
        }
        return List.copyOf(result);
    }

    static List<Step> schedule() {
        List<Step> result = new ArrayList<>();
        for (int repeat = 1; repeat <= 2; repeat++) {
            var order = repeat == 1 ? SIZES : List.of(20, 10, 5, 2);
            // HTTP 요청 조건을 교차할 뿐 각 크기의 그룹/그룹 내 순서는 그대로다.
            for (int batchIndex = 0; batchIndex < 10; batchIndex++) {
                for (int size : order) {
                    if (batchIndex < batches(size).size()) {
                        result.add(new Step(repeat, size, batchIndex));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    static String requestHash(Batch batch) {
        return Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.JSON.writeValueAsString(List.of(
                Issue55MicroBatchEvaluation.MODEL, Issue55MicroBatchEvaluation.SYSTEM_PROMPT,
                Issue55MicroBatchEvaluation.JSON.writeValueAsString(batch.input()),
                Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema(), "maxTokens=" + batch.outputCap() + ";sampling=unspecified")));
    }

    static List<Issue55MicroBatchComparison.Forecast> forecast() {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        List<Issue55MicroBatchComparison.Forecast> result = new ArrayList<>();
        for (int size : SIZES) {
            var batches = batches(size);
            long input = 2 * batches.stream().mapToLong(b -> encoding.countTokens(Issue55MicroBatchEvaluation.SYSTEM_PROMPT)
                    + encoding.countTokens(Issue55MicroBatchEvaluation.JSON.writeValueAsString(b.input()))
                    + encoding.countTokens(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()) + 256).sum();
            long cap = 2L * 20 * 128;
            double cost = Issue55MicroBatchComparison.cost(input, cap, 0);
            result.add(new Issue55MicroBatchComparison.Forecast(size, batches.size() * 2, input, cap, cost, cost * 4));
        }
        return List.copyOf(result);
    }

    static List<Group> groups(List<Call> calls) {
        List<Group> result = new ArrayList<>();
        for (int repeat = 1; repeat <= 2; repeat++) {
            for (int size : SIZES) {
                var selected = select(calls, repeat, size);
                if (selected.size() == batches(size).size()) {
                    result.add(new Group(repeat, size, Issue55MicroBatchF0.stats(
                            selected.stream().map(Call::response).toList(), selected.stream().mapToInt(Call::httpAttempts).sum(),
                            selected.stream().mapToLong(Call::processingNanos).sum())));
                }
            }
        }
        return List.copyOf(result);
    }

    static List<Series> series(List<Call> calls) {
        List<Series> result = new ArrayList<>();
        for (int size : SIZES) {
            var r1 = select(calls, 1, size);
            var r2 = select(calls, 2, size);
            if (r1.size() != batches(size).size() || r2.size() != batches(size).size()) {
                continue;
            }
            var a = Issue55MicroBatchF0.cases(r1.stream().map(Call::response).toList());
            var b = Issue55MicroBatchF0.cases(r2.stream().map(Call::response).toList());
            for (int i = 0; i < a.size(); i++) {
                var values = java.util.stream.Stream.of(a.get(i).actual(), b.get(i).actual()).toList();
                result.add(new Series(a.get(i).caseId(), size, a.get(i).expected(), values,
                        !Objects.equals(values.get(0), values.get(1))));
            }
        }
        return List.copyOf(result);
    }

    static List<Comparison> comparisons(List<Call> calls) {
        List<Comparison> result = new ArrayList<>();
        for (int repeat = 1; repeat <= 2; repeat++) {
            for (int a = 0; a < SIZES.size(); a++) {
                for (int b = a + 1; b < SIZES.size(); b++) {
                    int smaller = SIZES.get(a);
                    int larger = SIZES.get(b);
                    var reference = select(calls, repeat, smaller);
                    var compared = select(calls, repeat, larger);
                    if (reference.size() != batches(smaller).size() || compared.size() != batches(larger).size()) {
                        continue;
                    }
                    var changes = Issue55MicroBatchComparison.changes(
                            Issue55MicroBatchF0.cases(reference.stream().map(Call::response).toList()),
                            Issue55MicroBatchF0.cases(compared.stream().map(Call::response).toList()));
                    result.add(new Comparison(repeat, smaller, larger, changes, changes.stream()
                            .filter(c -> c.expected().riskLevel() == RiskLevel.HIGH && c.single().riskLevel() == RiskLevel.HIGH
                                    && c.batch().riskLevel() != RiskLevel.HIGH).map(c -> c.caseId()).toList()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Call> select(List<Call> calls, int repeat, int size) {
        return calls.stream().filter(c -> c.step().repeat() == repeat && c.step().batchSize() == size)
                .sorted(java.util.Comparator.comparingInt(c -> c.step().batchIndex())).toList();
    }

    private Issue55MicroBatchSizeComparison() { }
}
