package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

// 별도 Human 승인 대상인 N1만 표현한다. 과거 Control HOLD나 GT를 변경하지 않는다.
public final class Issue68A1N1Plan implements Issue68ExecutionGate {
    public static final String APPROVAL = "issue68-a1-native-n1-eight-v1";
    public static final String APPROVAL_ENV = "ISSUE68_A1_N1_LIVE_APPROVAL";
    public static final String MANIFEST_ENV = "ISSUE68_A1_N1_MANIFEST_SHA256";
    public static final String PRIOR_SHA = "80607e4746092d22067d61b818e69bd61f493e197f1a6d4d4cc58789c4fa6547";
    public static final String PROMPT_SHA = "c4a3f361f7325ac811f9e2f0ce35b3cd1934b536910ac89fbd903ca35e1c7960";
    public static final long LIMIT = 100_000_000L;
    public static final int HTTP_LIMIT = 32;
    public static final int HTTP_PER_LOGICAL = 4;
    public static final List<String> IDS = List.of("HOLDOUT-PROFANITY-14", "HOLDOUT-PROFANITY-15",
            "HOLDOUT-PROFANITY-16", "HOLDOUT-PROFANITY-01", "HOLDOUT-PROFANITY-17",
            "HOLDOUT-PROFANITY-13", "HOLDOUT-SAFE-17", "HOLDOUT-SAFE-01");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final Map<String, List<String>> holds = new LinkedHashMap<>();
    private final List<Issue68GateV2.Evaluation> evaluations = new ArrayList<>();
    private final Map<Long, Integer> http = new LinkedHashMap<>();
    private int admitted;
    private int committed;
    private boolean stopped;

    public static List<Issue68EvaluationContract.Case> cases() {
        var all = Issue68EvaluationContract.heldout();
        return IDS.stream().map(id -> all.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow()).toList();
    }

    public static Map<String, Object> manifest() {
        Issue68GateV2.requireFrozen();
        if (!PROMPT_SHA.equals(Issue68EvaluationContract.sha256(Issue68RiskBoundaryPrompt.CANDIDATE))) {
            throw new IllegalStateException("ISSUE68_A1_PROMPT_CHANGED");
        }
        Map<String, Object> value = new TreeMap<>();
        value.put("version", APPROVAL);
        value.put("requiresSeparateHumanPaidApproval", true);
        value.put("scope", "A1_NATIVE_N1_ONLY_NO_N2_BATCH_CONTROL_OR_REPEAT");
        value.put("promptVersion", Issue68RiskBoundaryPrompt.VERSION);
        value.put("promptSha256", PROMPT_SHA);
        value.put("controlSha256", Issue68EvaluationContract.sha256(ModerationPrompt.SYSTEM_PROMPT));
        value.put("schemaSha256", Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(false)));
        value.put("model", Issue68EvaluationContract.MODEL);
        value.put("policyVersion", ModerationPrompt.POLICY_VERSION);
        value.put("groundTruthSha256", Issue68EvaluationContract.groundTruthSha256());
        value.put("metadataSha256", Issue68GateV2.metadataSha256());
        value.put("gateV1", "HISTORICAL_HOLD_PRESERVED_SEPARATE_EVALUATION");
        value.put("gateV2", Issue68GateV2.EXECUTION_VERSION + "; candidate FP is blocking, no Native baseline waiver");
        value.put("orderedInputs", cases().stream().map(c -> Map.of("id", c.id(),
                "contentSha256", Issue68EvaluationContract.sha256(c.content()), "expected", c.expected())).toList());
        value.put("limits", Map.of("logical", 8, "http", HTTP_LIMIT, "httpPerLogical", HTTP_PER_LOGICAL,
                "diagnosticBurdenNanoUsd", LIMIT, "globalBurdenNanoUsd", Issue68Budget.EXPANDED_LIMIT));
        value.put("reservation", "128000 input + 128 output per exchange; no cache discount; UNKNOWN never refunded");
        value.put("journal", ".issue68-live/budget.jsonl; append-only; same global lock; no automatic resume");
        value.put("priorJournalSha256", PRIOR_SHA);
        value.put("priorBurdenNanoUsd", 19_723_050L);
        value.put("priorUnknownNanoUsd", 19_276_800L);
        value.put("priorSettledNanoUsd", 446_250L);
        value.put("priorGrossNanoUsd", 77_184_000L);
        value.put("endpoint", "https://api.openai.com/v1/chat/completions");
        value.put("transport", "POST/HTTPS443/noProxy/noRedirect/60sCallTimeout; every exchange admitted and reserved");
        value.put("sampling", "unchanged/unset; n=1; stream=false; max_tokens=128; no extra options");
        value.put("consumer", "partition3/oneUsed/concurrency1/maxPoll10/RECORD/autoCommitFalse; sequential publish after prior DB+offset");
        value.put("database", "isolated Testcontainers MySQL8.4/real JPA/afterCommit; no existing DB");
        value.put("automaticNextStage", false);
        value.put("normalWarningCoverage", "INSUFFICIENT; new synthetic20 excluded pending Human GT freeze");
        value.put("dbPromptVersion", "Production version unchanged; actual A1 wire version identified by this manifest");
        try {
            // 기존 source 동결 방식을 재사용하되 확장 Matrix/승인 권한은 가져오지 않는다.
            var existing = Issue68ExpandedPlan.manifest();
            value.put("sourceSha256", existing.get("sourceSha256"));
            value.put("productionSourceSha256", existing.get("productionSourceSha256"));
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("ISSUE68_A1_N1_SOURCE_UNAVAILABLE");
        }
        return value;
    }

    public static String manifestSha256() { return Issue68EvaluationContract.sha256(JSON.writeValueAsString(manifest())); }

    @Override public synchronized void begin(List<String> ids, boolean batch) {
        requireActive();
        if (batch || admitted != committed || admitted >= IDS.size() || !ids.equals(List.of(IDS.get(admitted)))) {
            stop();
            throw new IllegalStateException("ISSUE68_A1_N1_INPUT_OR_LOGICAL_LIMIT");
        }
        admitted++;
    }

    synchronized void beforeHttp(long logical) {
        requireActive();
        if (admitted != committed + 1 || logical != admitted || http.values().stream().mapToInt(i -> i).sum() >= HTTP_LIMIT
                || http.getOrDefault(logical, 0) >= HTTP_PER_LOGICAL) {
            stop();
            throw new IllegalStateException("ISSUE68_A1_N1_HTTP_LIMIT");
        }
        http.merge(logical, 1, Integer::sum);
    }

    @Override public synchronized void committed(Issue68EvaluationContract.Case c, ModerationResult actual, boolean saved) {
        if (committed >= admitted || !cases().get(committed).equals(c)) {
            stop();
            throw new IllegalStateException("ISSUE68_A1_N1_COMMIT_MAPPING");
        }
        var evaluation = Issue68GateV2.evaluate(c, actual, saved);
        evaluations.add(evaluation);
        committed++;
        // Candidate는 Native라도 FP/Category/Risk 오류를 기준선 면제하지 않는다.
        if (!evaluation.v2Blockers().isEmpty()) {
            holds.put(c.id(), evaluation.v2Blockers());
            stop();
        }
    }

    public synchronized boolean complete() { return !stopped && admitted == 8 && committed == 8; }
    public synchronized List<Issue68GateV2.Evaluation> evaluations() { return List.copyOf(evaluations); }
    @Override public synchronized Map<String, List<String>> holds() { return Map.copyOf(holds); }
    @Override public synchronized void requireActive() {
        if (stopped) throw new IllegalStateException("ISSUE68_A1_N1_HOLD");
    }
    @Override public synchronized void stop() { stopped = true; }
    @Override public synchronized boolean stopped() { return stopped; }
}
