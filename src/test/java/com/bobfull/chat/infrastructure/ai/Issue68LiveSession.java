package com.bobfull.chat.infrastructure.ai;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

// 고정 장부와 일회성 승인 claim을 묶는다. 과거 파일이 있으면 복원 정보만 보존하고 자동 재개하지 않는다.
public final class Issue68LiveSession implements AutoCloseable {
    public static final String APPROVAL = "issue68-smoke-native1-batch1-2026-10-21";
    public static final String RESUME_APPROVAL = "issue68-resume-usage-v1-native1-batch1-2026-10-21";
    public static final int RESUME_HTTP_LIMIT = 4;
    public static final String PRIOR_JOURNAL_SHA256 = "a8a259adbc6d559a585bcb821586b266ff18456c42f5aaf526a4618e86ef7cc5";
    private final Issue68Budget budget;
    private final Path directory;
    private boolean boundaryIssued;
    public static final String NATIVE_REQUEST_SHA256 = "88f2c1c0cfb1b850da5f1c6671501fd99e319a1f51e4cf8e1dcf0e2dd9b56324";
    public static final String BATCH_REQUEST_SHA256 = "e4737a4c08f33c81e245e9d16922b872208682f85ab0bcfd90fbb7c599955340";

    public static Issue68LiveSession open() throws IOException {
        if (!APPROVAL.equals(System.getenv("ISSUE68_LIVE_APPROVAL"))) {
            throw new IOException("ISSUE68_LIVE_NOT_APPROVED");
        }
        Path root = Path.of("").toRealPath();
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new IOException("ISSUE68_REPOSITORY_ROOT_REQUIRED");
        }
        return new Issue68LiveSession(root.resolve(".issue68-live"));
    }

    public static Issue68LiveSession resume() throws IOException {
        if (!RESUME_APPROVAL.equals(System.getenv("ISSUE68_LIVE_APPROVAL"))) {
            throw new IOException("ISSUE68_RESUME_NOT_APPROVED");
        }
        Path root = Path.of("").toRealPath();
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new IOException("ISSUE68_REPOSITORY_ROOT_REQUIRED");
        }
        return new Issue68LiveSession(root.resolve(".issue68-live"), RESUME_APPROVAL);
    }

    // 승인마다 새로운 manifest를 만들지만 wallet/과거 claim은 교체하지 않는다.
    Issue68LiveSession(Path root, String resumeApproval) throws IOException {
        if (!RESUME_APPROVAL.equals(resumeApproval)
                || !Issue68EvaluationContract.FROZEN_GT_SHA256.equals(Issue68EvaluationContract.groundTruthSha256())
                || Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("ISSUE68_RESUME_STATE_INVALID");
        }
        Path journal = root.resolve("budget.jsonl");
        Path oldClaim = root.resolve("approval.claim");
        directory = root.resolve(RESUME_APPROVAL);
        if (!Files.isRegularFile(journal) || Files.isSymbolicLink(journal)
                || !Files.isRegularFile(oldClaim) || Files.isSymbolicLink(oldClaim)
                || !Files.readString(oldClaim).equals(APPROVAL + "\n" + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n")
                || Files.exists(directory)) {
            throw new IOException("ISSUE68_RESUME_REAPPROVAL_REQUIRED");
        }
        budget = Issue68Budget.restoreSmoke(journal); // 동일 journal의 OS 잠금이 전체 실행을 소유한다.
        try {
            if (!PRIOR_JOURNAL_SHA256.equals(Issue68EvaluationContract.sha256(Files.readString(journal)))
                    || budget.attempts() != 1 || budget.chargedNanos() != 19_276_800L
                    || budget.grossReservedNanos() != 19_276_800L) {
                throw new IOException("ISSUE68_PRIOR_LEDGER_CHANGED");
            }
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            var manifest = new java.util.LinkedHashMap<String, Object>();
            manifest.put("approval", RESUME_APPROVAL);
            manifest.put("previousApproval", APPROVAL);
            manifest.put("recordedAt", java.time.Instant.now().toString());
            manifest.put("priorJournalSha256", PRIOR_JOURNAL_SHA256);
            manifest.put("priorJournalBytes", Files.size(journal));
            manifest.put("priorUnknownReservedNanoUsd", budget.chargedNanos());
            manifest.put("priorGrossReservedNanoUsd", budget.grossReservedNanos());
            manifest.put("firstNewAttempt", budget.attempts() + 1);
            manifest.put("maxNewHttpAttempts", RESUME_HTTP_LIMIT);
            manifest.put("maxNativeLogicalRequests", 1);
            manifest.put("maxBatchLogicalRequests", 1);
            manifest.put("grossLimitNanoUsd", 100_000_000L);
            manifest.put("plannedIssueLimitNanoUsd", Issue68Budget.PLANNED_LIMIT);
            manifest.put("model", Issue68EvaluationContract.MODEL);
            manifest.put("groundTruthSha256", Issue68EvaluationContract.FROZEN_GT_SHA256);
            manifest.put("nativeRequestSha256", NATIVE_REQUEST_SHA256);
            manifest.put("batchRequestSha256", BATCH_REQUEST_SHA256);
            manifest.put("automaticRestartAllowed", false);
            Path path = directory.resolve("approval-manifest.json");
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                var bytes = java.nio.ByteBuffer.wrap(new tools.jackson.databind.json.JsonMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            // 디렉터리 항목도 동기화한다. 실패 시 claim을 지우거나 자동 재개하지 않는다.
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
            try (var channel = FileChannel.open(root, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    Issue68LiveSession(Path directory) throws IOException {
        this.directory = directory;
        if (!Issue68EvaluationContract.FROZEN_GT_SHA256.equals(Issue68EvaluationContract.groundTruthSha256())) {
            throw new IOException("ISSUE68_GT_CHANGED");
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("ISSUE68_LIVE_PATH_INVALID");
        }
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path journal = directory.resolve("budget.jsonl");
        boolean existed = Files.exists(journal);
        if (!existed && Files.exists(directory.resolve("approval.claim"))) {
            throw new IOException("ISSUE68_JOURNAL_MISSING_REAPPROVAL_REQUIRED");
        }
        budget = Issue68Budget.restoreSmoke(journal);
        try {
            if (existed || Files.exists(directory.resolve("approval.claim"))) {
                throw new IOException("ISSUE68_REAPPROVAL_REQUIRED");
            }
            try (var claim = FileChannel.open(directory.resolve("approval.claim"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] value = (APPROVAL + "\n" + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(value);
                while (bytes.hasRemaining()) {
                    claim.write(bytes);
                }
                claim.force(true);
            }
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    public synchronized Issue68HttpBoundary boundary() {
        if (boundaryIssued) {
            throw new IllegalStateException("ISSUE68_BOUNDARY_ALREADY_ISSUED");
        }
        boundaryIssued = true;
        var boundary = new Issue68HttpBoundary(URI.create("https://api.openai.com/v1"), budget, true);
        boundary.checkpoints(this::checkpoint);
        return boundary;
    }

    public static void verifyFrozenRequest(boolean batch, String hash) {
        if (!(batch ? BATCH_REQUEST_SHA256 : NATIVE_REQUEST_SHA256).equals(hash)) {
            throw new IllegalStateException("ISSUE68_FROZEN_REQUEST_CHANGED");
        }
    }

    public synchronized void checkpoint(String name, Object value) {
        try {
            Path path = directory.resolve("checkpoint-" + name + ".json");
            String body = new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                    java.util.Map.of("recordedAt", java.time.Instant.now().toString(), "value", value,
                            "chargedNanoUsd", budget.chargedNanos(), "grossReservedNanoUsd", budget.grossReservedNanos()));
            // 같은 실행의 부분 상태다. 실패하면 다음 HTTP를 허용하지 않는다.
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                var bytes = java.nio.ByteBuffer.wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("ISSUE68_CHECKPOINT_FAILED");
        }
    }

    public Issue68Budget budget() {
        return budget;
    }

    public Path directory() {
        return directory;
    }

    public static List<Issue68EvaluationContract.Case> smokeCases() {
        return List.of("HOLDOUT-SAFE-01", "HOLDOUT-PROFANITY-16").stream()
                .map(id -> Issue68EvaluationContract.heldout().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow())
                .toList();
    }

    @Override
    public void close() throws IOException {
        budget.close();
    }
}
