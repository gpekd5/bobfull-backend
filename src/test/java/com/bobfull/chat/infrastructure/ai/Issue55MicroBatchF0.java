package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Batch;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.CaseObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Target;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// F0 고정 반복만 다룬다. 입력 섞기/Batch 구성 변경(F1/F2)은 구현하지 않는다.
final class Issue55MicroBatchF0 {
    static final double PRIOR_ESTIMATED_USD = .07891205;
    static final List<String> IDS = List.of(
            "HOLDOUT-SAFE-17", "HOLDOUT-PI-05", "HOLDOUT-PROFANITY-14", "HOLDOUT-SPAM-16",
            "HOLDOUT-SPAM-19", "HOLDOUT-SPAM-08", "HOLDOUT-SAFE-09", "HOLDOUT-PROFANITY-16",
            "HOLDOUT-PROFANITY-01", "HOLDOUT-PROFANITY-06", "HOLDOUT-PROFANITY-13", "HOLDOUT-SAFE-01",
            "HOLDOUT-PROFANITY-17", "HOLDOUT-SPAM-15", "HOLDOUT-PROFANITY-15", "HOLDOUT-PROFANITY-08",
            "HOLDOUT-SPAM-09", "HOLDOUT-SAFE-10", "HOLDOUT-PROFANITY-05", "HOLDOUT-PI-20");

    record Treatment(int repeat, int batchSize) { }
    record Manifest(String id, String neutralId, String messageSha256, ModerationResult expected) { }
    record Stats(Issue55MicroBatchComparison.Quality quality, int logicalCalls, int httpAttempts,
                 long inputTokens, long outputTokens, long cachedInputTokens, long totalTokens,
                 int cacheUnknownCalls, double noCacheEstimatedUsd, double cacheAwareEstimatedUsd,
                 Issue55MicroBatchComparison.Latency latency, double wallMs,
                 List<String> policyViolationIds, List<String> gtHighDowngradeIds) { }
    record Run(Treatment treatment, boolean complete, List<BatchObservation> calls, Stats stats) { }
    record Paired(int repeat, int batchSize, List<Issue55MicroBatchComparison.Change> changes,
                  List<String> highDowngradeFromSingle) { }
    record Variability(String caseId, int batchSize, List<ModerationResult> results,
                       int distinctTuples, int exactMatches, int unresolved) { }

    static List<Target> targets() {
        var byId = SpringAiModerationHeldoutEvaluationTest.heldoutCasesPolicyV2().stream()
                .collect(Collectors.toMap(c -> c.id(), Function.identity()));
        List<Target> targets = new ArrayList<>();
        var rule = new ModerationRulePolicy();
        for (int i = 0; i < IDS.size(); i++) {
            var c = byId.get(IDS.get(i));
            if (c == null || rule.clearFlagged(c.message()).isPresent()) {
                throw new IllegalStateException("F0 manifest must contain existing Rule-miss cases only");
            }
            targets.add(new Target("m%03d".formatted(i + 1), c));
        }
        return List.copyOf(targets);
    }

    static List<Batch> batches(int size) {
        if (!List.of(1, 5, 10).contains(size)) {
            throw new IllegalArgumentException("F0 supports only 1/5/10");
        }
        var targets = targets();
        List<Batch> batches = new ArrayList<>();
        for (int i = 0; i < targets.size(); i += size) {
            batches.add(new Batch("f0-b%02d-%02d".formatted(size, batches.size() + 1),
                    targets.subList(i, Math.min(i + size, targets.size()))));
        }
        return List.copyOf(batches);
    }

    static List<Treatment> schedule() {
        return List.of(new Treatment(1, 1), new Treatment(1, 5), new Treatment(1, 10),
                new Treatment(2, 5), new Treatment(2, 10), new Treatment(2, 1),
                new Treatment(3, 10), new Treatment(3, 1), new Treatment(3, 5));
    }

    static List<Manifest> manifest() {
        return targets().stream().map(t -> new Manifest(t.testCase().id(), t.neutralId(),
                sha256(t.testCase().message()), Issue55MicroBatchEvaluation.expected(t.testCase()))).toList();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // Local tokenizer estimate, not Provider Usage. Framing margin 128 per call; output uses full cap.
    static List<Issue55MicroBatchComparison.Forecast> forecast() {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        List<Issue55MicroBatchComparison.Forecast> values = new ArrayList<>();
        for (int size : List.of(1, 5, 10)) {
            var batches = batches(size);
            long input = 3 * batches.stream().mapToLong(b -> encoding.countTokens(size == 1
                    ? ModerationPrompt.SYSTEM_PROMPT : Issue55MicroBatchEvaluation.SYSTEM_PROMPT)
                    + encoding.countTokens(size == 1 ? b.targets().get(0).testCase().message()
                    : Issue55MicroBatchEvaluation.JSON.writeValueAsString(b.input()))
                    + encoding.countTokens(size == 1 ? Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()
                    : Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()) + 128).sum();
            long cap = 3L * 20 * 128;
            double cost = Issue55MicroBatchComparison.cost(input, cap, 0);
            values.add(new Issue55MicroBatchComparison.Forecast(size, 3 * batches.size(), input, cap, cost, cost * 4));
        }
        return List.copyOf(values);
    }

    static Stats stats(List<BatchObservation> calls, int attempts, long wallNanos) {
        var cases = cases(calls);
        var usages = calls.stream().map(BatchObservation::usage).filter(java.util.Objects::nonNull).toList();
        long input = usages.stream().mapToLong(u -> u.prompt()).sum();
        long output = usages.stream().mapToLong(u -> u.completion()).sum();
        long cached = usages.stream().mapToLong(u -> u.cachedInput() == null ? 0 : u.cachedInput()).sum();
        var ns = calls.stream().map(BatchObservation::callNanos).sorted().toList();
        var latency = new Issue55MicroBatchComparison.Latency(ns.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6,
                ns.get((int) Math.ceil(ns.size() * .95) - 1) / 1e6,
                ns.get((int) Math.ceil(ns.size() * .99) - 1) / 1e6, ns.get(ns.size() - 1) / 1e6);
        return new Stats(Issue55MicroBatchComparison.quality(cases), calls.size(), attempts, input, output, cached,
                usages.stream().mapToLong(u -> u.total()).sum(), (int) usages.stream().filter(u -> u.cachedInput() == null).count(),
                Issue55MicroBatchComparison.cost(input, output, 0), Issue55MicroBatchComparison.cost(input, output, cached),
                latency, wallNanos / 1e6, cases.stream().filter(c -> policyViolation(c.actual())).map(CaseObservation::caseId).toList(),
                cases.stream().filter(c -> c.actual() != null && c.expected().riskLevel() == RiskLevel.HIGH
                        && c.actual().riskLevel() != RiskLevel.HIGH).map(CaseObservation::caseId).toList());
    }

    static boolean policyViolation(ModerationResult result) {
        // 기존 Validator를 강화하지 않고 현재 Prompt의 PROFANITY LOW 금지만 별도 진단한다.
        return result != null && result.result() == ModerationResultType.FLAGGED
                && result.categories().contains(ModerationCategory.PROFANITY) && result.riskLevel() == RiskLevel.LOW;
    }

    static List<CaseObservation> cases(List<BatchObservation> calls) {
        return calls.stream().flatMap(c -> c.cases().stream()).toList();
    }

    static List<Paired> paired(List<Run> runs) {
        List<Paired> result = new ArrayList<>();
        for (var run : runs) {
            if (!run.complete() || run.treatment().batchSize() == 1) {
                continue;
            }
            var single = runs.stream().filter(r -> r.complete() && r.treatment().batchSize() == 1
                    && r.treatment().repeat() == run.treatment().repeat()).findFirst();
            if (single.isEmpty()) {
                continue;
            }
            var changes = Issue55MicroBatchComparison.changes(cases(single.get().calls()), cases(run.calls()));
            result.add(new Paired(run.treatment().repeat(), run.treatment().batchSize(), changes,
                    changes.stream().filter(c -> c.expected().riskLevel() == RiskLevel.HIGH
                            && c.single().riskLevel() == RiskLevel.HIGH && c.batch().riskLevel() != RiskLevel.HIGH)
                            .map(Issue55MicroBatchComparison.Change::caseId).toList()));
        }
        return List.copyOf(result);
    }

    static List<Variability> variability(List<Run> runs) {
        List<Variability> result = new ArrayList<>();
        for (int size : List.of(1, 5, 10)) {
            var selected = runs.stream().filter(r -> r.complete() && r.treatment().batchSize() == size)
                    .sorted(java.util.Comparator.comparingInt(r -> r.treatment().repeat())).toList();
            if (selected.size() != 3) {
                continue;
            }
            for (String id : IDS) {
                var rows = selected.stream().map(r -> cases(r.calls()).stream().filter(c -> id.equals(c.caseId()))
                        .findFirst().orElseThrow()).toList();
                // Stream.toList() retains null UNRESOLVED values; never replace missing output with SAFE.
                var values = rows.stream().map(CaseObservation::actual).toList();
                result.add(new Variability(id, size, values,
                        (int) values.stream().filter(java.util.Objects::nonNull).distinct().count(),
                        (int) rows.stream().filter(c -> c.expected().equals(c.actual())).count(),
                        (int) values.stream().filter(java.util.Objects::isNull).count()));
            }
        }
        return List.copyOf(result);
    }

    private Issue55MicroBatchF0() { }
}
