package com.bobfull.chat.infrastructure.ai;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

// Manifest 작성은 승인/전송이 아니다. open만 명시적 유료 승인 후 고정 기존 장부를 연다.
public final class Issue68ExpandedLiveSession implements AutoCloseable {
    public static final String APPROVAL = "issue68-six-condition-pilot120-gate-v2-v1";
    public static final String APPROVAL_ENV = "ISSUE68_PILOT_LIVE_APPROVAL";
    public static final String MANIFEST_ENV = "ISSUE68_PILOT_MANIFEST_SHA256";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final Issue68ExpandedPlan plan = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT);
    private final Issue68Budget budget;
    private final Path directory;
    private final String approvedHash;
    private final boolean rehearsal;
    private final List<Issue68HttpBoundary> boundaries = new ArrayList<>();
    private final java.util.Set<Issue68ExpandedPlan.Unit> opened = new HashSet<>();
    private boolean closed;
    private int sequence;

    public static Issue68ExpandedLiveSession open() throws IOException {
        // Key뿐 아니라 고정 journal/claim에 접근하기 전 승인 부재를 차단한다.
        if (!APPROVAL.equals(System.getenv(APPROVAL_ENV))) {
            throw new IOException("ISSUE68_PILOT_PAID_APPROVAL_REQUIRED");
        }
        Path root = Path.of("").toRealPath();
        if (!Files.isDirectory(root.resolve(".git"))) throw new IOException("ISSUE68_REPOSITORY_ROOT_REQUIRED");
        return new Issue68ExpandedLiveSession(root.resolve(".issue68-live"), System.getenv(MANIFEST_ENV), false);
    }

    // 유료 회계/claim/manifest와 같은 코드를 통과하되 목적지는 loopback으로만 고정한다.
    public static Issue68ExpandedLiveSession rehearsal(Path root) throws IOException {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!root.toRealPath().startsWith(temporary)) throw new IOException("ISSUE68_REHEARSAL_TEMP_ONLY");
        return new Issue68ExpandedLiveSession(root, manifestSha256(), true);
    }

    private Issue68ExpandedLiveSession(Path root, String manifestHash, boolean rehearsal) throws IOException {
        approvedHash = manifestSha256();
        this.rehearsal = rehearsal;
        if (!approvedHash.equals(manifestHash) || Files.isSymbolicLink(root) || !Files.isDirectory(root)
                || !root.toAbsolutePath().normalize().equals(root.toRealPath())) {
            throw new IOException("ISSUE68_PILOT_APPROVAL_OR_PATH_CHANGED");
        }
        Path priorClaim = root.resolve("approval.claim");
        if (Files.isSymbolicLink(priorClaim) || !Files.isRegularFile(priorClaim)
                || !Files.readString(priorClaim).equals(Issue68LiveSession.APPROVAL + "\n"
                        + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n")) {
            throw new IOException("ISSUE68_PILOT_PRIOR_APPROVAL_REQUIRED");
        }
        directory = root.resolve(APPROVAL);
        if (Files.exists(directory)) throw new IOException("ISSUE68_PILOT_REAPPROVAL_REQUIRED");
        budget = Issue68Budget.startExpandedLivePilot(root.resolve("budget.jsonl"), approvedHash);
        try {
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            durableNew(directory.resolve("approval-manifest.json"), JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(Map.of("manifestSha256", approvedHash, "manifest", manifest(),
                            "execution", rehearsal ? "LOOPBACK_REHEARSAL_NOT_LIVE" : "SEPARATELY_APPROVED_LIVE")));
            forceDirectory(root);
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    public static Map<String, Object> manifest() throws IOException {
        Map<String, Object> result = new TreeMap<>(Issue68ExpandedPlan.manifest());
        result.put("version", "issue68-six-condition-pilot-live-readiness-v1");
        result.put("approvalId", APPROVAL);
        result.put("scope", "PILOT120_ONLY_NATIVE_POLL_TIME0_20_50_100_BURST20_EACH");
        result.put("requiresSeparateHumanPaidApproval", true);
        result.put("matrix", Issue68ExpandedPlan.matrix().stream()
                .filter(u -> u.stage() == Issue68ExpandedPlan.Stage.PILOT).toList());
        result.put("orderedInputs", Issue68ExpandedPlan.cases(Issue68ExpandedPlan.Stage.PILOT).stream()
                .map(c -> Map.of("id", c.id(), "sha256", Issue68EvaluationContract.sha256(c.content()))).toList());
        result.put("stageLimits", List.of(Map.of("stage", "PILOT", "burdenNanoUsd", 100_000_000L, "httpLimit", 480)));
        result.put("endpoint", "https://api.openai.com/v1/chat/completions");
        result.put("transport", "POST/HTTPS443/noProxy/noRedirect/60sCallTimeout/65sPollLeaseMargin");
        result.put("journal", ".issue68-live/budget.jsonl; existing prefix required; append only; single file lock");
        result.put("priorUnknownNanoUsd", 19_276_800L);
        result.put("priorSettledNanoUsd", 311_100L);
        result.put("priorBurdenNanoUsd", 19_587_900L);
        result.put("priorGrossNanoUsd", 57_907_200L);
        result.put("reservation", Map.of("inputTokenCeiling", 128_000, "inputNanoUsdPerToken", 150,
                "outputNanoUsdPerToken", 600, "outputCapPerItem", 128, "cacheDiscountForGuard", false));
        result.put("stops", "technical/usageUnknown/budget/mapping/FN/reviewMiss/criticalDown/newFP/categoryRegression; no next HTTP");
        result.put("gateV1", "HISTORICAL_BATCH_GATE_HOLD_PRESERVED; separately reported, never retro PASS");
        result.put("automaticRestartAllowed", false);
        result.put("automaticNextStage", false);
        result.put("credential", "approved child process OPENAI_API_KEY only; never manifest/checkpoint/log");
        return result;
    }

    public static String manifestSha256() throws IOException {
        return Issue68EvaluationContract.sha256(JSON.writeValueAsString(manifest()));
    }

    public Issue68ExpandedPlan plan() { return plan; }
    public Issue68Budget budget() { return budget; }

    public Issue68HttpBoundary boundary() {
        if (rehearsal) throw new IllegalStateException("ISSUE68_REHEARSAL_HTTPS_BLOCKED");
        return attach(Issue68HttpBoundary.expandedLivePilot(budget, plan, this::requireApproval));
    }

    public Issue68HttpBoundary rehearsalBoundary(URI endpoint) {
        if (!rehearsal) throw new IllegalStateException("ISSUE68_LIVE_LOOPBACK_BLOCKED");
        return attach(Issue68HttpBoundary.expandedPreparation(endpoint, budget, plan));
    }

    // TLS socket을 만들지 않고 같은 Live URL/요청 검증기를 검사하는 package-private seam이다.
    Issue68HttpBoundary httpsInspectionBoundary() {
        if (!rehearsal) throw new IllegalStateException("ISSUE68_INSPECTION_REHEARSAL_ONLY");
        return attach(Issue68HttpBoundary.expandedLivePilot(budget, plan, this::requireApproval));
    }

    private Issue68HttpBoundary attach(Issue68HttpBoundary boundary) {
        try {
            requireApproval();
            if (plan.active() == null || !opened.add(plan.active())) throw new IllegalStateException("ISSUE68_PILOT_UNIT_REOPENED");
            boundary.authorization(this::requireApproval);
            boundary.checkpoints(this::checkpoint);
            boundaries.add(boundary);
            return boundary;
        } catch (RuntimeException failure) {
            boundary.close();
            plan.stop();
            throw failure;
        }
    }

    private void requireApproval() {
        try {
            if (closed || !approvedHash.equals(manifestSha256())) throw new IOException("changed");
            plan.requireActive();
            if (budget.blocked()) throw new IOException("budget blocked");
        } catch (IOException failure) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_PILOT_APPROVAL_CHANGED");
        }
    }

    public synchronized void checkpoint(String name, Object value) {
        if (closed || !name.matches("[a-z-]+")) throw new IllegalStateException("ISSUE68_CHECKPOINT_NAME");
        try {
            durableNew(directory.resolve("%05d-%s.json".formatted(++sequence, name)), JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(Map.of("scope", rehearsal ? "LIVE_BOUNDARY_LOOPBACK_REHEARSAL" : "LIVE_PILOT120",
                            "name", name, "data", value, "totalBurdenNanoUsd", budget.chargedNanos(),
                            "newPilotBurdenNanoUsd", budget.stageChargedNanos())));
        } catch (IOException failure) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_PILOT_CHECKPOINT_FAILED");
        }
    }

    public void completePilot() {
        checkpoint("pilot-complete", Map.of("complete", plan.complete(), "automaticNextStage", false));
        budget.completeExpandedStage(plan);
    }

    private static void durableNew(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        forceDirectory(path.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    @Override public void close() throws IOException {
        closed = true;
        plan.stop();
        boundaries.forEach(Issue68HttpBoundary::close);
        budget.close();
    }
}
