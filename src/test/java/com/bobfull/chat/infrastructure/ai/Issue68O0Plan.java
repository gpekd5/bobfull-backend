package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.service.ModerationRulePolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 유료 실행 승인이 아닌 O0 준비 계약. 실제 LiveSession은 기존 Smoke 범위를 유지한다.
public final class Issue68O0Plan implements Issue68ExecutionGate {
    public enum Mode { NATIVE, POLL }

    private final Set<String> allowed;
    private final Map<Mode, Set<String>> seen = Map.of(Mode.NATIVE, new HashSet<>(), Mode.POLL, new HashSet<>());
    private final String metadataHash = Issue68GateV2.metadataSha256();
    private Mode mode = Mode.NATIVE;
    private boolean stopped;
    private final Map<String, com.bobfull.chat.application.result.ModerationResult> nativeResults = new java.util.HashMap<>();
    private final Map<String, List<String>> executionHolds = new java.util.LinkedHashMap<>();

    public Issue68O0Plan() {
        Issue68GateV2.requireFrozen();
        var rule = new ModerationRulePolicy();
        allowed = Issue68EvaluationContract.heldout().stream().filter(c -> rule.clearFlagged(c.content()).isEmpty())
                .map(Issue68EvaluationContract.Case::id).collect(Collectors.toUnmodifiableSet());
        if (allowed.size() != 79 || !Issue68EvaluationContract.FROZEN_GT_SHA256
                .equals(Issue68EvaluationContract.groundTruthSha256())) {
            throw new IllegalStateException("ISSUE68_O0_DATASET_CHANGED");
        }
    }

    public synchronized void begin(List<String> ids, boolean batch) {
        if (stopped || !metadataHash.equals(Issue68GateV2.metadataSha256())
                || ids.isEmpty() || ids.size() > 10 || (!batch && ids.size() != 1)
                || batch && (mode == Mode.NATIVE || ids.size() < 2)
                || new HashSet<>(ids).size() != ids.size() || !allowed.containsAll(ids)
                || ids.stream().anyMatch(seen.get(mode)::contains)) {
            stopped = true;
            throw new IllegalStateException("ISSUE68_O0_LOGICAL_CONTRACT");
        }
        seen.get(mode).addAll(ids);
    }

    public synchronized void beginPoll(List<Issue68GateV2.Evaluation> nativeResults, boolean technicalOk) {
        Set<String> all = Issue68EvaluationContract.heldout().stream().map(Issue68EvaluationContract.Case::id)
                .collect(Collectors.toSet());
        if (stopped || mode != Mode.NATIVE || !technicalOk || !seen.get(Mode.NATIVE).equals(allowed)
                || nativeResults.size() != 80 || !nativeResults.stream().map(Issue68GateV2.Evaluation::id)
                        .collect(Collectors.toSet()).equals(all)
                || nativeResults.stream().anyMatch(e -> {
                    var c = cases().stream().filter(item -> item.id().equals(e.id())).findFirst().orElseThrow();
                    boolean nativeFp = c.expected().result() == com.bobfull.chat.domain.entity.ModerationResultType.SAFE
                            && e.v2Blockers().contains("FP_HUMAN_REVIEW");
                    var blocking = e.v2Blockers().stream().filter(reason -> !nativeFp || !Set.of(
                            "FP_HUMAN_REVIEW", "CATEGORY_MISMATCH", "OTHER_RISK_MISMATCH_HUMAN_REVIEW").contains(reason)).toList();
                    return !blocking.isEmpty();
                })) {
            stopped = true;
            throw new IllegalStateException("ISSUE68_O0_NATIVE_GATE_HOLD");
        }
        mode = Mode.POLL;
    }

    // 실제 JPA afterCommit 이후에만 호출한다. HOLD는 저장을 되돌리지 않고 다음 HTTP를 차단한다.
    public synchronized void committed(Issue68EvaluationContract.Case c,
            com.bobfull.chat.application.result.ModerationResult actual, boolean committed) {
        var holds = Issue68GateV2.executionBlockers(c, actual, committed, mode == Mode.NATIVE, nativeResults.get(c.id()));
        if (mode == Mode.NATIVE && actual != null) {
            nativeResults.put(c.id(), actual);
        }
        if (!holds.isEmpty()) {
            executionHolds.put(c.id(), holds);
            stopped = true;
        }
    }

    public synchronized Map<String, List<String>> holds() {
        return Map.copyOf(executionHolds);
    }

    public synchronized void requireActive() {
        if (stopped) {
            throw new IllegalStateException("ISSUE68_O0_QUALITY_OR_TECHNICAL_HOLD");
        }
    }

    public synchronized void stop() {
        stopped = true;
    }

    public synchronized boolean stopped() {
        return stopped;
    }

    public static List<Issue68EvaluationContract.Case> cases() {
        return Issue68EvaluationContract.ordered(55);
    }

    public static Map<String, Object> frozenContract() {
        return Map.ofEntries(Map.entry("groundTruthSha256", Issue68EvaluationContract.groundTruthSha256()),
                Map.entry("metadataSha256", Issue68GateV2.metadataSha256()),
                Map.entry("gateExecutionVersion", Issue68GateV2.EXECUTION_VERSION),
                Map.entry("model", Issue68EvaluationContract.MODEL),
                Map.entry("promptVersion", ModerationPrompt.PROMPT_VERSION), Map.entry("policyVersion", ModerationPrompt.POLICY_VERSION),
                Map.entry("outputTokensPerItem", 128), Map.entry("sampling", "provider defaults; no added temperature or seed"),
                Map.entry("systemSha256", Issue68EvaluationContract.sha256(ModerationPrompt.SYSTEM_PROMPT)),
                Map.entry("batchSystemSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.batchSystemPrompt())),
                Map.entry("nativeSchemaSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(false))),
                Map.entry("batchSchemaSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(true))),
                Map.entry("orderedInputSha256", Issue68EvaluationContract.sha256(cases().stream().map(c -> c.id() + "|"
                        + Issue68EvaluationContract.sha256(c.content())).collect(Collectors.joining("\n")))),
                Map.entry("nativeLogicalLimit", 79), Map.entry("pollLogicalLimit", 79),
                Map.entry("totalHttpLimit", Issue68Budget.O0_HTTP_LIMIT), Map.entry("newStageBurdenLimitNanoUsd", Issue68Budget.O0_STAGE_LIMIT),
                Map.entry("overallBurdenLimitNanoUsd", Issue68Budget.PLANNED_LIMIT),
                Map.entry("stageOrder", "Native80 (ordinary FP recorded) -> safety/technical Gate -> Poll80 + paired Gate; stop before next HTTP; no automatic repeat"));
    }
}
