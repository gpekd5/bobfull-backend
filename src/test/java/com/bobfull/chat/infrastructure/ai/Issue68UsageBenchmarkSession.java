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
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

// 각 단계의 별도 유료 승인 ID+해시 없이는 journal/Key 접근 전에 차단한다. 자동 단계 전환은 없다.
public final class Issue68UsageBenchmarkSession implements AutoCloseable {
    public static final String APPROVAL_ENV = "ISSUE68_USAGE_BENCHMARK_APPROVAL";
    public static final String MANIFEST_ENV = "ISSUE68_USAGE_BENCHMARK_MANIFEST_SHA256";
    private final Issue68UsageBenchmarkPlan plan;
    private final Issue68Budget budget;
    private final Path directory;
    private final String manifestHash;
    private final boolean rehearsal;
    private final List<Issue68HttpBoundary> boundaries = new ArrayList<>();
    private Path approvalRecord;
    private String approvalRecordSha;
    private boolean closed;
    private int sequence;

    public static Issue68UsageBenchmarkSession openBurst() throws IOException {
        return open(Issue68UsageBenchmarkPlan.Stage.BURST);
    }
    public static Issue68UsageBenchmarkSession openRemainder() throws IOException {
        return open(Issue68UsageBenchmarkPlan.Stage.REMAINDER);
    }
    private static Issue68UsageBenchmarkSession open(Issue68UsageBenchmarkPlan.Stage stage) throws IOException {
        if (!stage.approval().equals(System.getenv(APPROVAL_ENV))) throw new IOException("ISSUE68_BENCHMARK_PAID_APPROVAL_REQUIRED");
        var root = Path.of("").toRealPath();
        if (!Files.isDirectory(root.resolve(".git"))) throw new IOException("ISSUE68_REPOSITORY_ROOT_REQUIRED");
        return new Issue68UsageBenchmarkSession(root.resolve(".issue68-live"), stage, System.getenv(MANIFEST_ENV), false);
    }
    public static Issue68UsageBenchmarkSession rehearsal(Path root, Issue68UsageBenchmarkPlan.Stage stage) throws IOException {
        if (!root.toRealPath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())) throw new IOException("ISSUE68_BENCHMARK_TEMP_ONLY");
        return new Issue68UsageBenchmarkSession(root, stage, Issue68UsageBenchmarkPlan.manifestSha256(stage), true);
    }
    private Issue68UsageBenchmarkSession(Path root, Issue68UsageBenchmarkPlan.Stage stage, String hash, boolean rehearsal) throws IOException {
        this.rehearsal = rehearsal; plan = new Issue68UsageBenchmarkPlan(stage);
        manifestHash = Issue68UsageBenchmarkPlan.manifestSha256(stage);
        if (!manifestHash.equals(hash) || Files.isSymbolicLink(root) || !root.toAbsolutePath().normalize().equals(root.toRealPath())) {
            throw new IOException("ISSUE68_BENCHMARK_APPROVAL_OR_PATH");
        }
        directory = root.resolve(stage.approval());
        if (Files.exists(directory)) throw new IOException("ISSUE68_BENCHMARK_REAPPROVAL_REQUIRED");
        budget = rehearsal ? Issue68Budget.startBenchmark(root.resolve("budget.jsonl"), stage, hash)
                : Issue68Budget.startBenchmarkLive(root.resolve("budget.jsonl"), stage, hash);
        try {
            Files.createDirectory(directory); Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            checkpoint("approval-manifest", Map.of("manifestSha256", hash, "manifest", Issue68UsageBenchmarkPlan.manifest(stage),
                    "execution", rehearsal ? "FAKE_NOT_MODEL_QUALITY" : "SEPARATELY_APPROVED_NON_OPERATIONAL_USAGE"));
            approvalRecord = directory.resolve("00001-approval-manifest.json");
            approvalRecordSha = Issue68EvaluationContract.sha256(Files.readString(approvalRecord));
        } catch (Exception failure) { budget.close(); throw failure; }
    }
    public Issue68UsageBenchmarkPlan plan() { return plan; }
    public Issue68Budget budget() { return budget; }
    public synchronized Issue68HttpBoundary boundary() {
        if (rehearsal) throw new IllegalStateException("ISSUE68_BENCHMARK_REHEARSAL_HTTPS_BLOCKED");
        return attach(URI.create("https://api.openai.com/v1"), true, false);
    }
    public synchronized Issue68HttpBoundary rehearsalBoundary(URI endpoint) {
        if (!rehearsal) throw new IllegalStateException("ISSUE68_BENCHMARK_LIVE_LOOPBACK_BLOCKED");
        return attach(endpoint, false, false);
    }
    synchronized Issue68HttpBoundary httpsInspectionBoundary() {
        if (!rehearsal) throw new IllegalStateException("ISSUE68_INSPECTION_REHEARSAL_ONLY");
        return attach(URI.create("https://api.openai.com/v1"), true, true);
    }
    private Issue68HttpBoundary attach(URI endpoint, boolean live, boolean inspection) {
        requireApproval();
        if (plan.active() == null || boundaries.stream().anyMatch(b -> !b.o0Held() && b.usageBenchmark() && !b.calls().isEmpty()
                && !b.calls().get(b.calls().size() - 1).failed && b.calls().get(b.calls().size() - 1).ended == 0)) {
            plan.stop(); throw new IllegalStateException("ISSUE68_BENCHMARK_BOUNDARY_SCOPE");
        }
        // 같은 unit의 boundary를 다시 열어 local HTTP counter를 초기화하지 않는다.
        if (!opened.add(plan.active())) { plan.stop(); throw new IllegalStateException("ISSUE68_BENCHMARK_BOUNDARY_REOPENED"); }
        var boundary = Issue68HttpBoundary.usageBenchmark(endpoint, budget, plan, live, this::requireApproval);
        if (inspection) boundary.inspectionOnly();
        boundary.checkpoints(this::checkpoint); boundaries.add(boundary); return boundary;
    }
    private final java.util.Set<Issue68ExpandedPlan.Unit> opened = new java.util.HashSet<>();
    private synchronized void requireApproval() {
        try {
            if (closed || budget.blocked() || Files.isSymbolicLink(approvalRecord)
                    || !approvalRecordSha.equals(Issue68EvaluationContract.sha256(Files.readString(approvalRecord)))
                    || !manifestHash.equals(Issue68UsageBenchmarkPlan.manifestSha256(plan.stage()))
                    || !rehearsal && (!plan.stage().approval().equals(System.getenv(APPROVAL_ENV)) || !manifestHash.equals(System.getenv(MANIFEST_ENV)))) {
                throw new IllegalStateException("ISSUE68_BENCHMARK_APPROVAL_CHANGED");
            }
            plan.requireActive();
        } catch (IOException | RuntimeException failure) { plan.stop(); throw new IllegalStateException("ISSUE68_BENCHMARK_APPROVAL_HOLD"); }
    }
    public synchronized void checkpoint(String name, Object data) {
        if (closed || !name.matches("[a-z-]+")) { plan.stop(); throw new IllegalStateException("ISSUE68_BENCHMARK_CHECKPOINT_NAME"); }
        try {
            var file = directory.resolve("%05d-%s.json".formatted(++sequence, name));
            byte[] bytes = new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                    "scope", rehearsal ? "FAKE_USAGE_BENCHMARK_NOT_MODEL_QUALITY" : "LIVE_USAGE_BENCHMARK_NOT_ADOPTION",
                    "name", name, "atUtc", java.time.Instant.now().toString(), "data", data,
                    "burdenNanoUsd", budget.chargedNanos(), "benchmarkBurdenNanoUsd", budget.benchmarkChargedNanos(),
                    "stageBurdenNanoUsd", budget.stageChargedNanos()));
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        } catch (IOException failure) { plan.stop(); throw new IllegalStateException("ISSUE68_BENCHMARK_CHECKPOINT_FAILED"); }
    }
    public void completeStage() {
        plan.requireActive(); budget.completeBenchmark(plan);
        checkpoint("stage-measurement-complete", Map.of("qualityStatus", "HISTORICAL_GATE_HOLD_PRESERVED",
                "adoptionStatus", "NOT_APPROVED", "automaticNextStage", false));
        plan.stop();
    }
    @Override public void close() throws IOException { closed = true; plan.stop(); boundaries.forEach(Issue68HttpBoundary::close); budget.close(); }
}
