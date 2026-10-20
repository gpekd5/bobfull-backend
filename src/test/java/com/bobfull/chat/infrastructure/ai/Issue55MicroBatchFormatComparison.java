package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Batch;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Target;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;

// A 실험만 추가한다. 기존 F0/Prompt/비교 실험과 Production 계약은 수정하지 않는다.
final class Issue55MicroBatchFormatComparison {
    static final double PRIOR_ESTIMATED_USD = .09170180;
    enum Mode { NATIVE, BATCH1 }
    enum Judgment { BOTH_CORRECT, NATIVE_ONLY_CORRECT, BATCH1_ONLY_CORRECT, BOTH_WRONG, UNRESOLVED }
    record Step(int repeat, int targetIndex, Mode mode) { }
    record Forecast(Mode mode, int logicalCalls, long tokenizerEstimatedInput, long outputCapSum,
                    double noCacheEstimateAtCapUsd, double allFourAttemptsEstimateUsd) { }
    record Call(Step step, String caseId, String requestSpecSha256, String startedAt, long processingNanos,
                int httpAttempts, BatchObservation response) { }
    record Pair(int repeat, String caseId, ModerationResult expected, ModerationResult nativeResult,
                ModerationResult batch1Result, boolean resultEqual, boolean categoriesEqual, boolean riskEqual,
                boolean fullEqual, Judgment fullJudgment, Judgment resultCategoryJudgment,
                boolean newFp, boolean newFn, boolean gtHighDowngradeFromNative) { }
    record Series(String caseId, ModerationResult expected, List<ModerationResult> nativeResults,
                  List<ModerationResult> batch1Results, boolean nativeChanged, boolean batch1Changed) { }
    record Group(int repeat, Mode mode, Issue55MicroBatchF0.Stats stats) { }

    static List<Step> schedule() {
        List<Step> result = new ArrayList<>();
        for (int repeat = 1; repeat <= 2; repeat++) {
            for (int i = 0; i < 20; i++) {
                // R1은 Case별 선행 조건을 교차하고 R2는 각 Case의 순서를 반대로 한다.
                boolean nativeFirst = (i + repeat) % 2 == 1;
                result.add(new Step(repeat, i, nativeFirst ? Mode.NATIVE : Mode.BATCH1));
                result.add(new Step(repeat, i, nativeFirst ? Mode.BATCH1 : Mode.NATIVE));
            }
        }
        return List.copyOf(result);
    }

    static Batch batch1(Target target) {
        return new Batch("a-" + target.neutralId(), List.of(target));
    }

    static BatchObservation evaluate(ChatClient client, Mode mode, Target target, int index) {
        return mode == Mode.NATIVE ? Issue55MicroBatchComparison.single(client, target, index + 1)
                : Issue55MicroBatchEvaluation.evaluate(client, batch1(target));
    }

    static String requestHash(Mode mode, Target target) {
        String system = mode == Mode.NATIVE ? ModerationPrompt.SYSTEM_PROMPT : Issue55MicroBatchEvaluation.SYSTEM_PROMPT;
        String user = mode == Mode.NATIVE ? target.testCase().message()
                : Issue55MicroBatchEvaluation.JSON.writeValueAsString(batch1(target).input());
        String schema = mode == Mode.NATIVE ? Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()
                : Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema();
        // Semantic request specification only. 인증/HTTP header를 포함한 wire hash가 아니다.
        return Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.JSON.writeValueAsString(
                List.of(Issue55MicroBatchEvaluation.MODEL, system, user, schema, "maxTokens=128;sampling=unspecified")));
    }

    static List<Forecast> forecast() {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        List<Forecast> forecasts = new ArrayList<>();
        for (var mode : Mode.values()) {
            long input = 2 * Issue55MicroBatchF0.targets().stream().mapToLong(t ->
                    encoding.countTokens(mode == Mode.NATIVE ? ModerationPrompt.SYSTEM_PROMPT : Issue55MicroBatchEvaluation.SYSTEM_PROMPT)
                    + encoding.countTokens(mode == Mode.NATIVE ? t.testCase().message()
                    : Issue55MicroBatchEvaluation.JSON.writeValueAsString(batch1(t).input()))
                    + encoding.countTokens(mode == Mode.NATIVE ? Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()
                    : Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()) + 256).sum();
            double cost = Issue55MicroBatchComparison.cost(input, 40 * 128, 0);
            forecasts.add(new Forecast(mode, 40, input, 40L * 128, cost, cost * 4));
        }
        return List.copyOf(forecasts);
    }

    static List<Pair> pairs(List<Call> calls) {
        List<Pair> result = new ArrayList<>();
        for (int repeat = 1; repeat <= 2; repeat++) {
            for (String id : Issue55MicroBatchF0.IDS) {
                Call nativeCall = find(calls, repeat, Mode.NATIVE, id);
                Call batchCall = find(calls, repeat, Mode.BATCH1, id);
                if (nativeCall == null || batchCall == null) {
                    continue;
                }
                var n = nativeCall.response().cases().get(0);
                var b = batchCall.response().cases().get(0);
                var expected = n.expected();
                var a = n.actual();
                var z = b.actual();
                boolean valid = a != null && z != null;
                result.add(new Pair(repeat, id, expected, a, z,
                        valid && a.result() == z.result(), valid && a.categories().equals(z.categories()),
                        valid && a.riskLevel() == z.riskLevel(), valid && a.equals(z),
                        judgment(expected, a, z, true), judgment(expected, a, z, false),
                        valid && expected.result() == ModerationResultType.SAFE
                                && a.result() == ModerationResultType.SAFE && z.result() == ModerationResultType.FLAGGED,
                        valid && expected.result() == ModerationResultType.FLAGGED
                                && a.result() == ModerationResultType.FLAGGED && z.result() == ModerationResultType.SAFE,
                        valid && expected.riskLevel() == RiskLevel.HIGH && a.riskLevel() == RiskLevel.HIGH
                                && z.riskLevel() != RiskLevel.HIGH));
            }
        }
        return List.copyOf(result);
    }

    static Judgment judgment(ModerationResult expected, ModerationResult nativeResult,
                             ModerationResult batchResult, boolean fullTuple) {
        if (nativeResult == null || batchResult == null) {
            return Judgment.UNRESOLVED;
        }
        boolean a = matches(expected, nativeResult, fullTuple);
        boolean b = matches(expected, batchResult, fullTuple);
        return a ? b ? Judgment.BOTH_CORRECT : Judgment.NATIVE_ONLY_CORRECT
                : b ? Judgment.BATCH1_ONLY_CORRECT : Judgment.BOTH_WRONG;
    }

    private static boolean matches(ModerationResult expected, ModerationResult actual, boolean fullTuple) {
        return fullTuple ? expected.equals(actual)
                : expected.result() == actual.result() && expected.categories().equals(actual.categories());
    }

    static List<Series> series(List<Call> calls) {
        List<Series> result = new ArrayList<>();
        for (String id : Issue55MicroBatchF0.IDS) {
            var n1 = find(calls, 1, Mode.NATIVE, id);
            var n2 = find(calls, 2, Mode.NATIVE, id);
            var b1 = find(calls, 1, Mode.BATCH1, id);
            var b2 = find(calls, 2, Mode.BATCH1, id);
            if (n1 == null || n2 == null || b1 == null || b2 == null) {
                continue;
            }
            var ns = java.util.stream.Stream.of(n1, n2).map(c -> c.response().cases().get(0).actual()).toList();
            var bs = java.util.stream.Stream.of(b1, b2).map(c -> c.response().cases().get(0).actual()).toList();
            result.add(new Series(id, n1.response().cases().get(0).expected(), ns, bs,
                    !Objects.equals(ns.get(0), ns.get(1)), !Objects.equals(bs.get(0), bs.get(1))));
        }
        return List.copyOf(result);
    }

    static List<Group> groups(List<Call> calls) {
        List<Group> result = new ArrayList<>();
        for (int repeat = 1; repeat <= 2; repeat++) {
            for (var mode : Mode.values()) {
                int r = repeat;
                var selected = calls.stream().filter(c -> c.step().repeat() == r && c.step().mode() == mode).toList();
                if (selected.size() == 20) {
                    result.add(new Group(repeat, mode, Issue55MicroBatchF0.stats(
                            selected.stream().map(Call::response).toList(), selected.stream().mapToInt(Call::httpAttempts).sum(),
                            selected.stream().mapToLong(Call::processingNanos).sum())));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Call find(List<Call> calls, int repeat, Mode mode, String id) {
        var found = calls.stream().filter(c -> c.step().repeat() == repeat && c.step().mode() == mode && c.caseId().equals(id)).toList();
        if (found.size() > 1) {
            throw new IllegalArgumentException("Duplicate treatment/case observation");
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private Issue55MicroBatchFormatComparison() { }
}
