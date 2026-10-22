package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.ai.converter.BeanOutputConverter;

// 과거 사용한 Policy v2 GT를 재사용한다. 새40건의 AI 제안 label은 가져오지 않는다.
public final class Issue68EvaluationContract {
    public static final String MODEL = "gpt-4o-mini";
    public static final String GATE_VERSION = "issue68-human-gate-v1";
    public static final String PROVENANCE = "historical-human-reviewed-policy-v2-not-blind";
    public static final String FROZEN_GT_SHA256 = "2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b";
    public static final int OUTPUT_PER_ITEM = 128;

    private Issue68EvaluationContract() {
    }

    public record Case(String id, String content, ModerationResult expected) {
    }

    public record Manifest(String id, String contentSha256, ModerationResult expected) {
    }

    public record Stage(String name, String input, int repeats, long seed, boolean sequential) {
    }

    public static List<Case> heldout() {
        return SpringAiModerationHeldoutEvaluationTest.heldoutCasesPolicyV2().stream()
                .map(c -> new Case(c.id(), c.message(), new ModerationResult(c.expectedResult(),
                        c.expectedCategories(), c.expectedRiskLevel())))
                .toList();
    }

    public static List<Case> diagnostic() {
        Map<String, Case> all = new LinkedHashMap<>();
        heldout().forEach(c -> all.put(c.id(), c));
        return Issue55MicroBatchF0.IDS.stream().map(all::get).toList();
    }

    public static List<Case> ordered(long seed) {
        List<Case> result = new ArrayList<>(heldout());
        Collections.shuffle(result, new Random(seed));
        return List.copyOf(result);
    }

    public static List<Manifest> manifest() {
        return heldout().stream().map(c -> new Manifest(c.id(), sha256(c.content()), c.expected())).toList();
    }

    public static String groundTruthSha256() {
        return sha256(manifest().stream().map(m -> m.id() + "|" + m.contentSha256() + "|"
                + m.expected().result() + "|" + m.expected().categories().stream().map(Enum::name).sorted().toList()
                + "|" + m.expected().riskLevel()).sorted().reduce((a, b) -> a + "\n" + b).orElseThrow());
    }

    public static List<Stage> matrix() {
        // Pilot도 상한에 포함한다. 각 stage는 별도의 Human 승인이 필요하다.
        return List.of(new Stage("diagnostic-underfill", "diagnostic20", 1, 55, true),
                new Stage("batch-pilot", "diagnostic20", 1, 55, false),
                new Stage("heldout-O0", "heldout80", 3, 55, false),
                new Stage("heldout-O1", "heldout80", 2, 68, false));
    }

    public static String batchSystemPrompt() {
        try {
            var instructions = SpringAiModerationBatchAdapter.class.getDeclaredField("BATCH_INSTRUCTIONS");
            instructions.setAccessible(true);
            return ModerationPrompt.SYSTEM_PROMPT + instructions.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static String schema(boolean batch) {
        if (!batch) {
            return new BeanOutputConverter<>(ModerationResult.class).getJsonSchema();
        }
        try {
            var converter = SpringAiModerationBatchAdapter.class.getDeclaredField("CONVERTER");
            converter.setAccessible(true);
            return ((BeanOutputConverter<?>) converter.get(null)).getJsonSchema();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("ISSUE68_SCHEMA_UNAVAILABLE");
        }
    }

    public record Quality(int cases, int unresolved, int resultExact, int categoryExact, int riskExact,
                          int actionabilityExact, int tp, int fp, int fn, int tn, double precision,
                          double recall, double f1, List<String> fpIds, List<String> fnIds, List<String> highMissIds) {
    }

    public static Quality quality(List<Case> cases, Map<String, ModerationResult> actual) {
        int unresolved = 0;
        int resultExact = 0;
        int categoryExact = 0;
        int riskExact = 0;
        int actionabilityExact = 0;
        int tp = 0;
        int tn = 0;
        List<String> fp = new ArrayList<>();
        List<String> fn = new ArrayList<>();
        List<String> high = new ArrayList<>();
        for (var c : cases) {
            var a = actual.get(c.id());
            if (a == null) {
                unresolved++;
                continue;
            }
            var expected = c.expected();
            resultExact += a.result() == expected.result() ? 1 : 0;
            categoryExact += a.categories().equals(expected.categories()) ? 1 : 0;
            riskExact += a.riskLevel() == expected.riskLevel() ? 1 : 0;
            actionabilityExact += (a.riskLevel() != RiskLevel.LOW) == (expected.riskLevel() != RiskLevel.LOW) ? 1 : 0;
            if (expected.result() == ModerationResultType.FLAGGED) {
                if (a.result() == ModerationResultType.FLAGGED) {
                    tp++;
                } else {
                    fn.add(c.id());
                }
            } else if (a.result() == ModerationResultType.FLAGGED) {
                fp.add(c.id());
            } else {
                tn++;
            }
            if (expected.riskLevel() == RiskLevel.HIGH && a.riskLevel() != RiskLevel.HIGH) {
                high.add(c.id());
            }
        }
        double precision = tp + fp.size() == 0 ? 0 : (double) tp / (tp + fp.size());
        double recall = tp + fn.size() == 0 ? 0 : (double) tp / (tp + fn.size());
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new Quality(cases.size(), unresolved, resultExact, categoryExact, riskExact, actionabilityExact,
                tp, fp.size(), fn.size(), tn, precision, recall, f1, List.copyOf(fp), List.copyOf(fn), List.copyOf(high));
    }

    public static List<String> gate(Case c, ModerationResult actual, boolean completed) {
        List<String> reasons = new ArrayList<>();
        if (!completed || actual == null) {
            reasons.add("UNRESOLVED");
            return List.copyOf(reasons);
        }
        if (c.expected().result() == ModerationResultType.FLAGGED && actual.result() == ModerationResultType.SAFE) {
            reasons.add("FN");
        }
        if (c.expected().riskLevel() == RiskLevel.HIGH && actual.riskLevel() != RiskLevel.HIGH) {
            reasons.add("GT_HIGH_DOWNGRADE");
        }
        if (Issue55MicroBatchF0.policyViolation(actual)) {
            reasons.add("POLICY_COMBINATION");
        }
        return List.copyOf(reasons);
    }

    public static List<String> pairedGate(Case c, ModerationResult nativeResult, ModerationResult batchResult) {
        if (nativeResult == null || batchResult == null) {
            return List.of("UNRESOLVED_PAIR");
        }
        List<String> reasons = new ArrayList<>();
        if (c.expected().result() == ModerationResultType.SAFE && nativeResult.result() == ModerationResultType.SAFE
                && batchResult.result() == ModerationResultType.FLAGGED) {
            reasons.add("NEW_FP");
        }
        if (nativeResult.result() == c.expected().result() && batchResult.result() != c.expected().result()) {
            reasons.add("RESULT_REGRESSION");
        }
        return List.copyOf(reasons);
    }

    public record Forecast(String stage, int nativeCalls, int onMinCalls, int onMaxCalls,
                           long estimatedInputMin, long estimatedInputMax, long outputCap,
                           long estimatedCostMinNanos, long estimatedCostMaxNanos) {
    }

    public static List<Forecast> forecast() {
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        var singleSchema = new BeanOutputConverter<>(ModerationResult.class).getJsonSchema();
        String batchSchema;
        String batchInstructions;
        try {
            // 실제 Production Schema/지시문을 읽으며 Production 가시성은 바꾸지 않는다.
            var converter = SpringAiModerationBatchAdapter.class.getDeclaredField("CONVERTER");
            converter.setAccessible(true);
            batchSchema = ((BeanOutputConverter<?>) converter.get(null)).getJsonSchema();
            var instructions = SpringAiModerationBatchAdapter.class.getDeclaredField("BATCH_INSTRUCTIONS");
            instructions.setAccessible(true);
            batchInstructions = (String) instructions.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        List<Forecast> forecasts = new ArrayList<>();
        for (Stage stage : matrix()) {
            List<Case> cases = stage.input().equals("diagnostic20") ? diagnostic() : ordered(stage.seed());
            var rule = new ModerationRulePolicy();
            var targets = cases.stream().filter(c -> rule.clearFlagged(c.content()).isEmpty()).toList();
            long nativeInput = targets.stream().mapToLong(c -> encoding.countTokens(ModerationPrompt.SYSTEM_PROMPT)
                    + encoding.countTokens(c.content()) + encoding.countTokens(singleSchema) + 128).sum();
            long onInput = 0;
            int onCalls = 0;
            for (int start = 0; start < targets.size(); start += stage.sequential() ? 1 : 10) {
                var group = targets.subList(start, Math.min(start + (stage.sequential() ? 1 : 10), targets.size()));
                if (group.size() == 1) {
                    onInput += encoding.countTokens(ModerationPrompt.SYSTEM_PROMPT)
                            + encoding.countTokens(group.get(0).content()) + encoding.countTokens(singleSchema) + 128;
                } else {
                    List<Map<String, String>> items = new ArrayList<>();
                    for (int i = 0; i < group.size(); i++) {
                        items.add(Map.of("id", "m%03d".formatted(i + 1), "content", group.get(i).content()));
                    }
                    onInput += encoding.countTokens(ModerationPrompt.SYSTEM_PROMPT + batchInstructions)
                            + encoding.countTokens(Issue55MicroBatchEvaluation.JSON.writeValueAsString(Map.of("items", items)))
                            + encoding.countTokens(batchSchema) + 128;
                }
                onCalls++;
            }
            long output = 2L * stage.repeats() * targets.size() * OUTPUT_PER_ITEM;
            long minInput = stage.repeats() * (nativeInput + onInput);
            long maxInput = stage.repeats() * 2 * nativeInput;
            forecasts.add(new Forecast(stage.name(), targets.size() * stage.repeats(), onCalls * stage.repeats(),
                    targets.size() * stage.repeats(), minInput, maxInput, output,
                    Issue68Budget.cost(minInput, output), Issue68Budget.cost(maxInput, output)));
        }
        return List.copyOf(forecasts);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
