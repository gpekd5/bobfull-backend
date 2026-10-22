package com.bobfull.chat.infrastructure.ai;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

// Key를 읽지 않는다. open은 향후 별도 유료 승인 환경변수가 있을 때만 기존 고정 장부에 연결한다.
public final class Issue68A1N1Session implements AutoCloseable {
    private static final JsonMapper JSON = new JsonMapper();
    private final Issue68A1N1Plan plan = new Issue68A1N1Plan();
    private final Issue68Budget budget;
    private final Path directory;
    private final String approvedHash;
    private final boolean rehearsal;
    private Issue68HttpBoundary boundary;
    private Path approvalRecord;
    private String approvalRecordSha;
    private boolean closed;
    private int sequence;

    public static Issue68A1N1Session open() throws IOException {
        if (!Issue68A1N1Plan.APPROVAL.equals(System.getenv(Issue68A1N1Plan.APPROVAL_ENV))) {
            throw new IOException("ISSUE68_A1_N1_PAID_APPROVAL_REQUIRED");
        }
        Path root = Path.of("").toRealPath();
        if (!Files.isDirectory(root.resolve(".git"))) throw new IOException("ISSUE68_REPOSITORY_ROOT_REQUIRED");
        return new Issue68A1N1Session(root.resolve(".issue68-live"), System.getenv(Issue68A1N1Plan.MANIFEST_ENV), false);
    }

    // 실제 journal 대신 byte 동일한 temp 복사본만 허용하고, 같은 승인·회계·checkpoint 코드를 검증한다.
    public static Issue68A1N1Session rehearsal(Path root) throws IOException {
        if (!root.toRealPath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())) {
            throw new IOException("ISSUE68_A1_N1_TEMP_ONLY");
        }
        return new Issue68A1N1Session(root, Issue68A1N1Plan.manifestSha256(), true);
    }

    private Issue68A1N1Session(Path root, String hash, boolean rehearsal) throws IOException {
        this.rehearsal = rehearsal;
        approvedHash = Issue68A1N1Plan.manifestSha256();
        if (!approvedHash.equals(hash) || Files.isSymbolicLink(root) || !root.toAbsolutePath().normalize().equals(root.toRealPath())) {
            throw new IOException("ISSUE68_A1_N1_APPROVAL_OR_PATH");
        }
        directory = root.resolve(Issue68A1N1Plan.APPROVAL);
        if (Files.exists(directory)) throw new IOException("ISSUE68_A1_N1_REAPPROVAL_REQUIRED");
        budget = Issue68Budget.startA1N1(root.resolve("budget.jsonl"), approvedHash);
        try {
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            checkpoint("approval-manifest", Map.of("manifestSha256", approvedHash, "manifest", Issue68A1N1Plan.manifest(),
                    "execution", rehearsal ? "FREE_LOOPBACK_REHEARSAL" : "SEPARATELY_APPROVED_A1_N1"));
            approvalRecord = directory.resolve("00001-approval-manifest.json");
            approvalRecordSha = Issue68EvaluationContract.sha256(Files.readString(approvalRecord));
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    public Issue68A1N1Plan plan() { return plan; }
    public Issue68Budget budget() { return budget; }

    public synchronized Issue68HttpBoundary boundary() {
        if (rehearsal) throw new IllegalStateException("ISSUE68_A1_N1_REHEARSAL_HTTPS_BLOCKED");
        return attach(URI.create("https://api.openai.com/v1"), true);
    }

    public synchronized Issue68HttpBoundary rehearsalBoundary(URI endpoint) {
        if (!rehearsal) throw new IllegalStateException("ISSUE68_A1_N1_LIVE_LOOPBACK_BLOCKED");
        return attach(endpoint, false);
    }

    // URL/본문 검사 전용이며 실제 socket을 열 수 없다.
    synchronized Issue68HttpBoundary httpsInspectionBoundary() {
        if (!rehearsal) throw new IllegalStateException("ISSUE68_A1_N1_INSPECTION_ONLY");
        return attach(URI.create("https://api.openai.com/v1"), true);
    }

    private Issue68HttpBoundary attach(URI endpoint, boolean https) {
        requireApproval();
        if (boundary != null) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_A1_N1_BOUNDARY_REOPENED");
        }
        try {
            boundary = Issue68HttpBoundary.a1N1(endpoint, budget, plan, https, this::requireApproval);
            boundary.authorization(this::requireApproval);
            boundary.checkpoints(this::checkpoint);
            // 검사 전용 HTTPS 객체는 실제 send 직전에 반드시 차단한다.
            if (rehearsal && https) boundary.inspectionOnly();
            return boundary;
        } catch (RuntimeException failure) {
            plan.stop();
            throw failure;
        }
    }

    private void requireApproval() {
        boolean recordMatches;
        try {
            recordMatches = !Files.isSymbolicLink(approvalRecord) && approvalRecordSha.equals(
                    Issue68EvaluationContract.sha256(Files.readString(approvalRecord)));
        } catch (IOException unavailable) {
            recordMatches = false;
        }
        if (closed || !recordMatches || budget.blocked() || !approvedHash.equals(Issue68A1N1Plan.manifestSha256())
                || !rehearsal && (!Issue68A1N1Plan.APPROVAL.equals(System.getenv(Issue68A1N1Plan.APPROVAL_ENV))
                || !approvedHash.equals(System.getenv(Issue68A1N1Plan.MANIFEST_ENV)))) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_A1_N1_APPROVAL_CHANGED");
        }
        plan.requireActive();
    }

    public synchronized void checkpoint(String name, Object data) {
        if (closed || !name.matches("[a-z-]+")) throw new IllegalStateException("ISSUE68_A1_N1_CHECKPOINT_NAME");
        try {
            Path file = directory.resolve("%05d-%s.json".formatted(++sequence, name));
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("scope",
                    rehearsal ? "FREE_A1_N1_REHEARSAL_NOT_MODEL_QUALITY" : "LIVE_A1_N1_NOT_ADOPTION",
                    "name", name, "atUtc", java.time.Instant.now().toString(), "data", data,
                    "burdenNanoUsd", budget.chargedNanos(), "diagnosticBurdenNanoUsd", budget.stageChargedNanos()));
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        } catch (IOException failure) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_A1_N1_CHECKPOINT_FAILED");
        }
    }

    public void complete() {
        if (boundary == null || boundary.calls().size() != 8 || !plan.complete()
                || boundary.sdkEntries().size() != 8 || boundary.calls().stream().anyMatch(c -> c.failed)
                || boundary.attempts().stream().anyMatch(a -> !a.settlement().equals("SETTLED"))) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_A1_N1_INCOMPLETE");
        }
        checkpoint("n-one-complete", Map.of("automaticNextStage", false, "notCandidateAdoption", true,
                "evaluations", plan.evaluations()));
        budget.completeA1N1(plan);
        plan.stop();
    }

    @Override public void close() throws IOException {
        closed = true;
        plan.stop();
        if (boundary != null) boundary.close();
        budget.close();
    }
}
