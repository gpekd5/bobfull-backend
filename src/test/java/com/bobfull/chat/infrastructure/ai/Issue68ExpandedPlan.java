package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Human 승인: 무과금 준비만. 이 클래스에 credential/HTTPS 실행 진입점은 없다.
public final class Issue68ExpandedPlan implements Issue68ExecutionGate {
    public static final String VERSION = "issue68-six-condition-preparation-v1";
    public enum Mode {
        NATIVE(-1), POLL(-1), TIME_0(0), TIME_20(20), TIME_50(50), TIME_100(100);
        public final int waitMs;
        Mode(int waitMs) { this.waitMs = waitMs; }
        public boolean owner() { return waitMs >= 0; }
        public boolean batchEnabled() { return this != NATIVE; }
    }
    public enum Traffic {
        LOW(250), INTERVAL(40), BURST(0), SHORT(5);
        public final int intervalMs;
        Traffic(int intervalMs) { this.intervalMs = intervalMs; }
    }
    public enum Stage {
        PILOT(20, 100_000_000L, 480), MAIN(80, 500_000_000L, 7584), REPEAT(80, 250_000_000L, 3792);
        public final int count;
        public final long burdenLimit;
        public final int httpLimit;
        Stage(int count, long burdenLimit, int httpLimit) {
            this.count = count;
            this.burdenLimit = burdenLimit;
            this.httpLimit = httpLimit;
        }
    }
    public record Unit(Stage stage, Traffic traffic, int repeat, Mode mode) { }
    public record Forecast(Stage stage, int messages, int logicalLowShape, int logicalUnderfill,
                           long inputLowShape, long inputUnderfill, long outputCap,
                           long costLowShapeNanoUsd, long costUnderfillNanoUsd) { }

    // 기존 무과금 estimator의 두 요청형만 재사용한다. Usage/보장 상한/실제 poll 예측이 아니다.
    public static List<Forecast> forecast() {
        var historical = Issue68EvaluationContract.forecast();
        List<Forecast> result = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            String name = stage == Stage.PILOT ? "batch-pilot" : "heldout-O0";
            var original = historical.stream().filter(f -> f.stage().equals(name)).findFirst().orElseThrow();
            int repeats = Issue68EvaluationContract.matrix().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow().repeats();
            long nativeInput = original.estimatedInputMax() / (2L * repeats);
            long batchInput = original.estimatedInputMin() / repeats - nativeInput;
            int nativeUnits = (int) matrix().stream().filter(u -> u.stage() == stage && u.mode() == Mode.NATIVE).count();
            int otherUnits = nativeUnits * 5;
            int targets = stage == Stage.PILOT ? 20 : 79;
            int calls = (targets + 9) / 10;
            long lowInput = nativeUnits * nativeInput + otherUnits * batchInput;
            long underfill = (nativeUnits + otherUnits) * nativeInput;
            long cap = (long) (nativeUnits + otherUnits) * targets * 128;
            result.add(new Forecast(stage, (nativeUnits + otherUnits) * stage.count,
                    nativeUnits * targets + otherUnits * calls, (nativeUnits + otherUnits) * targets,
                    lowInput, underfill, cap, Issue68Budget.cost(lowInput, cap), Issue68Budget.cost(underfill, cap)));
        }
        return List.copyOf(result);
    }

    private final Stage stage;
    private final List<Unit> units;
    private final Map<String, Issue68EvaluationContract.Case> cases;
    private final Set<String> targets;
    private final Map<String, Map<String, ModerationResult>> references = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private final Set<String> committed = new HashSet<>();
    private final Map<String, List<String>> holds = new LinkedHashMap<>();
    private final List<Issue68GateV2.Evaluation> evaluations = new ArrayList<>();
    private Unit active;
    private int cursor;
    private boolean batchObserved;
    private boolean stopped;

    public Issue68ExpandedPlan(Stage stage) {
        Issue68GateV2.requireFrozen();
        this.stage = stage;
        units = matrix().stream().filter(u -> u.stage() == stage).toList();
        cases = cases(stage).stream().collect(Collectors.toMap(Issue68EvaluationContract.Case::id,
                c -> c, (a, b) -> { throw new IllegalStateException("DUPLICATE_CASE"); }, LinkedHashMap::new));
        targets = cases.values().stream().filter(c -> new ModerationRulePolicy().clearFlagged(c.content()).isEmpty())
                .map(Issue68EvaluationContract.Case::id).collect(Collectors.toSet());
    }

    public static List<Issue68EvaluationContract.Case> cases(Stage stage) {
        return stage == Stage.PILOT ? Issue68EvaluationContract.diagnostic() : Issue68EvaluationContract.ordered(55);
    }

    public static List<Unit> matrix() {
        List<Unit> result = new ArrayList<>();
        addRound(result, Stage.PILOT, Traffic.BURST, 1, 0);
        for (var traffic : Traffic.values()) addRound(result, Stage.MAIN, traffic, 1, traffic.ordinal());
        for (int repeat = 2; repeat <= 3; repeat++) addRound(result, Stage.REPEAT, Traffic.BURST, repeat, repeat);
        return List.copyOf(result);
    }

    private static void addRound(List<Unit> result, Stage stage, Traffic traffic, int repeat, int rotation) {
        result.add(new Unit(stage, traffic, repeat, Mode.NATIVE));
        var treatments = List.of(Mode.POLL, Mode.TIME_0, Mode.TIME_20, Mode.TIME_50, Mode.TIME_100);
        for (int i = 0; i < treatments.size(); i++) {
            result.add(new Unit(stage, traffic, repeat, treatments.get((i + rotation) % treatments.size())));
        }
    }

    public synchronized void start(Unit unit) {
        requireActive();
        if (active != null || cursor >= units.size() || !units.get(cursor).equals(unit)) fail("UNIT_ORDER");
        active = unit;
        seen.clear();
        committed.clear();
        batchObserved = false;
        if (unit.mode() == Mode.NATIVE) references.put(referenceKey(), new HashMap<>());
    }

    @Override
    public synchronized void begin(List<String> ids, boolean batch) {
        requireActive();
        if (active == null || ids.isEmpty() || ids.size() > 10 || (!batch && ids.size() != 1)
                || (batch && (active.mode() == Mode.NATIVE || ids.size() < 2))
                || new HashSet<>(ids).size() != ids.size() || !targets.containsAll(ids)
                || ids.stream().anyMatch(seen::contains)) fail("LOGICAL_CONTRACT");
        seen.addAll(ids);
        batchObserved |= batch;
    }

    @Override
    public synchronized void committed(Issue68EvaluationContract.Case c, ModerationResult actual, boolean completed) {
        if (active == null || !cases.containsKey(c.id()) || !cases.get(c.id()).equals(c)
                || !committed.add(c.id()) || completed && targets.contains(c.id()) && !seen.contains(c.id())) {
            fail("DB_MAPPING");
        }
        var reference = references.get(referenceKey());
        var reasons = Issue68GateV2.executionBlockers(c, actual, completed, active.mode() == Mode.NATIVE,
                reference == null ? null : reference.get(c.id()));
        evaluations.add(Issue68GateV2.evaluate(c, actual, completed));
        if (active.mode() == Mode.NATIVE && actual != null) reference.put(c.id(), actual);
        if (!reasons.isEmpty()) {
            holds.put(active + "/" + c.id(), reasons);
            stopped = true;
        }
    }

    // 개별 DB Commit만으로 다음 조건에 진입하지 않는다. 호출자가 Lag/Offset/DLT/Usage도 확정한다.
    public synchronized void completeUnit(boolean technicalOk) {
        requireActive();
        if (active == null || !technicalOk || !seen.equals(targets) || !committed.equals(cases.keySet())
                || stage == Stage.PILOT && active.mode() != Mode.NATIVE && !batchObserved) fail("UNIT_INCOMPLETE");
        cursor++;
        active = null;
    }

    public synchronized boolean complete() { return !stopped && active == null && cursor == units.size(); }
    public synchronized Unit active() { return active; }
    public synchronized List<Issue68GateV2.Evaluation> evaluations() { return List.copyOf(evaluations); }
    public Stage stage() { return stage; }
    private String referenceKey() { return active.traffic() + "/" + active.repeat(); }
    private void fail(String reason) {
        holds.put("contract", List.of(reason));
        stopped = true;
        throw new IllegalStateException("ISSUE68_EXPANDED_" + reason);
    }
    @Override public synchronized Map<String, List<String>> holds() { return Map.copyOf(holds); }
    @Override public synchronized void stop() { stopped = true; }
    @Override public synchronized boolean stopped() { return stopped; }
    @Override public synchronized void requireActive() {
        if (stopped) throw new IllegalStateException("ISSUE68_EXPANDED_HOLD");
        Issue68GateV2.requireFrozen();
    }

    public static Map<String, Object> manifest() throws java.io.IOException {
        Map<String, String> sources = new TreeMap<>();
        for (String directory : List.of("src/test/java/com/bobfull/chat/infrastructure/ai",
                "src/test/java/com/bobfull/chat/infrastructure/kafka")) {
            try (var files = Files.list(Path.of(directory))) {
                for (var file : files.filter(p -> p.getFileName().toString().startsWith("Issue68")
                        && p.toString().endsWith(".java") || p.getFileName().toString().equals("SizeTimeBatchOwnerLoop.java")).toList()) {
                    sources.put(file.toString(), Issue68EvaluationContract.sha256(Files.readString(file)));
                }
            }
        }
        Map<String, Object> result = new TreeMap<>();
        result.put("version", VERSION);
        result.put("paidExecutionEnabled", false);
        result.put("matrix", matrix());
        result.put("groundTruthSha256", Issue68EvaluationContract.groundTruthSha256());
        result.put("metadataSha256", Issue68GateV2.metadataSha256());
        result.put("gateExecutionVersion", Issue68GateV2.EXECUTION_VERSION);
        result.put("model", Issue68EvaluationContract.MODEL);
        result.put("systemSha256", Issue68EvaluationContract.sha256(ModerationPrompt.SYSTEM_PROMPT));
        result.put("batchSystemSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.batchSystemPrompt()));
        result.put("nativeSchemaSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(false)));
        result.put("batchSchemaSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(true)));
        result.put("orderedInputs", Map.of("pilot", cases(Stage.PILOT).stream().map(c -> Map.of("id", c.id(), "sha256",
                Issue68EvaluationContract.sha256(c.content()))).toList(), "heldout", cases(Stage.MAIN).stream()
                .map(c -> Map.of("id", c.id(), "sha256", Issue68EvaluationContract.sha256(c.content()))).toList()));
        result.put("stageLimits", java.util.Arrays.stream(Stage.values()).map(s -> Map.of("stage", s,
                "burdenNanoUsd", s.burdenLimit, "httpLimit", s.httpLimit)).toList());
        result.put("trafficIntervalsMs", java.util.Arrays.stream(Traffic.values()).collect(Collectors.toMap(Enum::name, t -> t.intervalMs)));
        result.put("globalBurdenNanoUsd", 1_000_000_000L);
        result.put("outputPerItem", 128);
        result.put("consumer", "partition3/concurrency1/maxPoll10/buffer20/autoCommitFalse");
        result.put("sampling", "provider defaults; input order only seed55; no paid warmup");
        result.put("priorJournalSha256", Issue68Budget.O0_PRIOR_SHA256);
        result.put("restart", "audit only; no automatic stage transition or retry of stopped unit");
        result.put("sourceSha256", sources);
        Map<String, String> production = new TreeMap<>();
        for (String file : List.of("application/service/ChatModerationService.java",
                "application/service/ChatModerationBatchService.java", "application/service/ModerationRulePolicy.java",
                "application/service/SplitMessageCandidateGate.java", "application/service/ModerationResultValidator.java",
                "infrastructure/ai/ModerationPrompt.java", "infrastructure/ai/ModerationOpenAiOptions.java",
                "infrastructure/ai/SpringAiModerationAdapter.java", "infrastructure/ai/SpringAiModerationBatchAdapter.java",
                "infrastructure/kafka/ChatModerationConsumer.java", "infrastructure/kafka/ChatModerationBatchConsumer.java",
                "infrastructure/kafka/ChatModerationBatchRetryProcessor.java", "infrastructure/kafka/ChatModerationBatchConfig.java",
                "infrastructure/kafka/ChatModerationBatchErrorHandler.java", "infrastructure/kafka/ChatModerationDltRecoverer.java")) {
            var path = Path.of("src/main/java/com/bobfull/chat").resolve(file);
            production.put(path.toString(), Issue68EvaluationContract.sha256(Files.readString(path)));
        }
        result.put("productionSourceSha256", production);
        return result;
    }

    public static String manifestSha256() throws java.io.IOException {
        var json = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        return Issue68EvaluationContract.sha256(json.writeValueAsString(manifest()));
    }
}
