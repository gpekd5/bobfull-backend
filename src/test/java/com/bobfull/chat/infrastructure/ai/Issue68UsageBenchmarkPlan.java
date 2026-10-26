package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

// 비운영 사용량 측정 전용. 기존 품질 Gate의 결과를 변경하거나 채택 승인으로 바꾸지 않는다.
public final class Issue68UsageBenchmarkPlan implements Issue68ExecutionGate {
    public static final String VERSION = "issue68-usage-benchmark-v1";
    public static final String PRIOR_SHA = "35924fde9b6de4437b5294b9e23daf48322c51daa012c509f08aa15c893e01d0";
    public static final String HISTORICAL_A1_MANIFEST = "3d959747d3334bdf3d8fbc3ea911cdc8f6e5a09305bd30ce94a0cef915bd26cc";
    public static final String COMPLETED_BURST_JOURNAL_SHA = "8aef3df31bbb897987d827f0736dd30619702348866f7fe4ab76e111828fd3ac";
    public static final String COMPLETED_BURST_MANIFEST_SHA = "d61581f2947bda099a4277d0e90745c4fe8922032f61e3e1fa706ba27d68a0a9";
    public static final String COMPLETED_BURST_RESULTS = "docs/110-records/evidence/v3/68-openai-poll-batch-verification/usage-benchmark-burst-live-results.json";
    public static final String COMPLETED_BURST_RESULTS_SHA = "b9227f2eeddfa89bf679d3b7bb0458a97eb627cf7ad21da40babbe552dca3079";
    public static final long LIMIT = 500_000_000L;
    public static final int HTTP_LIMIT = 1920;
    public enum Stage {
        BURST(100_000_000L, 480, 120), REMAINDER(400_000_000L, 1440, 360);
        public final long burdenLimit;
        public final int httpLimit;
        public final int logicalLimit;
        Stage(long burden, int http, int logical) { burdenLimit = burden; httpLimit = http; logicalLimit = logical; }
        public String approval() { return "issue68-usage-benchmark-" + name().toLowerCase(java.util.Locale.ROOT) + "-v1"; }
    }
    private final Stage stage;
    private final Set<String> targets = Issue68EvaluationContract.diagnostic().stream()
            .map(Issue68EvaluationContract.Case::id).collect(java.util.stream.Collectors.toSet());
    private final Set<String> seen = new HashSet<>();
    private final Set<String> committed = new HashSet<>();
    private final Map<String, Integer> http = new LinkedHashMap<>();
    private final Map<String, List<String>> technical = new LinkedHashMap<>();
    private final List<Map<String, Object>> quality = new ArrayList<>();
    private Issue68ExpandedPlan.Unit active;
    private int cursor;
    private int logical;
    private int unitLogical;
    private boolean stopped;

    public Issue68UsageBenchmarkPlan(Stage stage) { this.stage = stage; Issue68GateV2.requireFrozen(); }
    public Stage stage() { return stage; }
    public static List<Issue68EvaluationContract.Case> cases() { return Issue68EvaluationContract.diagnostic(); }
    public static List<Issue68ExpandedPlan.Unit> units(Stage stage) {
        var units = new ArrayList<Issue68ExpandedPlan.Unit>();
        var traffic = stage == Stage.BURST ? List.of(Issue68ExpandedPlan.Traffic.BURST)
                : List.of(Issue68ExpandedPlan.Traffic.LOW, Issue68ExpandedPlan.Traffic.INTERVAL, Issue68ExpandedPlan.Traffic.SHORT);
        for (var pattern : traffic) {
            // 항상 같은 순서로 Native를 먼저 실행하지 않도록 조건을 고정 회전한다.
            var modes = Issue68ExpandedPlan.Mode.values();
            for (int i = 0; i < modes.length; i++) {
                units.add(new Issue68ExpandedPlan.Unit(Issue68ExpandedPlan.Stage.PILOT, pattern, 1,
                        modes[(i + pattern.ordinal()) % modes.length]));
            }
        }
        return List.copyOf(units);
    }
    public synchronized void start(Issue68ExpandedPlan.Unit unit) {
        requireActive();
        if (active != null || cursor >= units(stage).size() || !units(stage).get(cursor).equals(unit)) fail("UNIT_ORDER");
        active = unit; seen.clear(); committed.clear(); unitLogical = 0;
    }
    public synchronized Issue68ExpandedPlan.Unit active() { return active; }
    @Override public synchronized void begin(List<String> ids, boolean batch) {
        requireActive();
        if (active == null || ids.isEmpty() || ids.size() > 10 || !targets.containsAll(ids)
                || new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(seen::contains)
                || (!batch && ids.size() != 1) || (batch && (active.mode() == Issue68ExpandedPlan.Mode.NATIVE || ids.size() < 2))
                || logical >= stage.logicalLimit) fail("LOGICAL_CONTRACT");
        logical++; unitLogical++; seen.addAll(ids);
    }
    synchronized void beforeHttp(long localLogical) {
        requireActive();
        String key = active + "/" + localLogical;
        if (active == null || localLogical < 1 || localLogical != unitLogical
                || http.getOrDefault(key, 0) >= 4 || http.values().stream().mapToInt(i -> i).sum() >= stage.httpLimit) fail("HTTP_LIMIT");
        http.merge(key, 1, Integer::sum);
    }
    @Override public synchronized void committed(Issue68EvaluationContract.Case c, ModerationResult actual, boolean saved) {
        if (active == null || !cases().contains(c) || !seen.contains(c.id()) || !committed.add(c.id()) || !saved || actual == null) fail("DB_MAPPING");
        // 의미상 오류는 원래 evaluator로 계산하고 별도 기록. 기술적 중단 상태와 혼합하지 않는다.
        quality.add(Map.of("unit", active, "evaluation", Issue68GateV2.evaluate(c, actual, saved), "actual", actual));
    }
    public synchronized void completeUnit(boolean technicalOk) {
        requireActive();
        if (active == null || !technicalOk || !seen.equals(targets) || !committed.equals(targets)) fail("UNIT_INCOMPLETE");
        cursor++; active = null;
    }
    public synchronized boolean complete() { return !stopped && active == null && cursor == units(stage).size(); }
    public synchronized List<Map<String, Object>> quality() { return List.copyOf(quality); }
    public synchronized int logicalCount() { return logical; }
    private void fail(String reason) { technical.put("contract", List.of(reason)); stopped = true; throw new IllegalStateException("ISSUE68_BENCHMARK_" + reason); }
    @Override public synchronized Map<String, List<String>> holds() { return Map.copyOf(technical); }
    @Override public synchronized void stop() { stopped = true; }
    @Override public synchronized boolean stopped() { return stopped; }
    @Override public synchronized void requireActive() {
        if (stopped) throw new IllegalStateException("ISSUE68_BENCHMARK_TECHNICAL_HOLD");
        Issue68GateV2.requireFrozen();
    }
    public static Map<String, Object> manifest(Stage stage) {
        try {
            var value = new TreeMap<String, Object>(Issue68ExpandedPlan.manifest());
            value.remove("orderedInputs");
            value.remove("stageLimits");
            value.put("version", VERSION); value.put("approvalId", stage.approval());
            value.put("scope", "NON_OPERATIONAL_USAGE_ONLY_NOT_QUALITY_PASS_OR_ADOPTION");
            value.put("matrix", units(stage)); value.put("stage", stage);
            value.put("legacyUnitStage", "PILOT is only the reused diagnostic20 dataset selector, not the old quality Pilot approval");
            value.put("dataset", cases().stream().map(c -> Map.of("id", c.id(), "sha256", Issue68EvaluationContract.sha256(c.content()), "expected", c.expected())).toList());
            value.put("priorJournalSha256", PRIOR_SHA); value.put("priorBurdenNanoUsd", 19_881_750L);
            value.put("priorUnknownNanoUsd", 19_276_800L); value.put("priorSettledNanoUsd", 604_950L);
            value.put("priorHttpAttempts", 5); value.put("priorGrossReservedNanoUsd", 96_460_800L);
            if (stage == Stage.REMAINDER) {
                value.put("benchmarkInitialJournalSha256", PRIOR_SHA);
                value.put("benchmarkInitialBurdenNanoUsd", 19_881_750L);
                value.put("priorJournalSha256", COMPLETED_BURST_JOURNAL_SHA);
                value.put("priorBurdenNanoUsd", 25_923_000L);
                value.put("priorSettledNanoUsd", 6_646_200L);
                value.put("priorHttpAttempts", 36);
                value.put("priorGrossReservedNanoUsd", 700_876_800L);
                value.put("priorPendingNanoUsd", 0L);
                value.put("priorBenchmarkBurdenNanoUsd", 6_041_250L);
                value.put("completedBurst", Map.of("manifestSha256", COMPLETED_BURST_MANIFEST_SHA,
                        "resultsPath", COMPLETED_BURST_RESULTS, "resultsSha256", COMPLETED_BURST_RESULTS_SHA,
                        "observations", 120, "httpAttempts", 31));
            }
            value.put("requiresSeparateHumanPaidApproval", true); value.put("automaticNextStage", false);
            value.put("quality", "V1/V2 HOLD preserved; semantic errors recorded, technical errors block next HTTP");
            value.put("limits", Map.of("globalBurdenNanoUsd", 1_000_000_000L, "benchmarkBurdenNanoUsd", LIMIT,
                    "stageBurdenNanoUsd", stage.burdenLimit, "logical", 480, "stageLogical", stage.logicalLimit,
                    "http", HTTP_LIMIT, "stageHttp", stage.httpLimit, "httpPerLogical", 4));
            value.put("transport", "HTTPS443 api.openai.com/v1/chat/completions POST/noProxy/noRedirect; exchange reservation; UNKNOWN stops all Retry");
            value.put("inputContract", "Production System/Few-shot; Native schema vs unchanged Batch instructions/envelope; no A1");
            value.put("journal", ".issue68-live/budget.jsonl; original history retained; no new ledger/reset/automatic restart");
            value.put("stage2", "separate approval required after BURST completion; no automatic transition");
            return value;
        } catch (java.io.IOException failure) { throw new IllegalStateException("ISSUE68_BENCHMARK_SOURCE_UNAVAILABLE"); }
    }
    public static String manifestSha256(Stage stage) {
        return Issue68EvaluationContract.sha256(JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build().writeValueAsString(manifest(stage)));
    }
}
