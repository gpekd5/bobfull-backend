package com.bobfull.chat.infrastructure.ai;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

// 새로운 유료 실행 승인만 연결한다. 생성/manifest 조회는 API Key를 읽지 않는다.
public final class Issue68O0Session implements AutoCloseable {
    public static final String APPROVAL = "issue68-o0-native80-poll80-gate-v2-v1";
    public static final String APPROVAL_ENV = "ISSUE68_O0_LIVE_APPROVAL";
    public static final String MANIFEST_ENV = "ISSUE68_O0_MANIFEST_SHA256";
    private static final JsonMapper JSON = new JsonMapper();
    private final Issue68Budget budget;
    private final Issue68O0Plan plan = new Issue68O0Plan();
    private final Path directory;
    private final String approvedHash;
    private Issue68HttpBoundary boundary;
    private boolean closed;
    private int checkpointSequence;

    public static Issue68O0Session open() throws IOException {
        // 유료 승인 부재이면 journal, checkpoint, key에 접근하기 전에 거절한다.
        if (!APPROVAL.equals(System.getenv(APPROVAL_ENV))) {
            throw new IOException("ISSUE68_O0_PAID_APPROVAL_REQUIRED");
        }
        Path root = Path.of("").toRealPath();
        if (!Files.isDirectory(root.resolve(".git"))) {
            throw new IOException("ISSUE68_REPOSITORY_ROOT_REQUIRED");
        }
        return new Issue68O0Session(root.resolve(".issue68-live"), System.getenv(MANIFEST_ENV));
    }

    // 테스트에서는 공개 과거 장부와 같은 temp fixture만 사용한다. public 경로 선택 API는 없다.
    Issue68O0Session(Path root, String manifestHash) throws IOException {
        approvedHash = manifestSha256();
        if (!approvedHash.equals(manifestHash) || Files.isSymbolicLink(root) || !Files.isDirectory(root)
                || !root.toAbsolutePath().normalize().equals(root.toRealPath())) {
            throw new IOException("ISSUE68_O0_APPROVAL_OR_PATH_CHANGED");
        }
        Path oldClaim = root.resolve("approval.claim");
        if (Files.isSymbolicLink(oldClaim) || !Files.isRegularFile(oldClaim)
                || !Files.readString(oldClaim).equals(Issue68LiveSession.APPROVAL + "\n"
                        + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n")) {
            throw new IOException("ISSUE68_O0_PRIOR_APPROVAL_REQUIRED");
        }
        directory = root.resolve(APPROVAL);
        if (Files.exists(directory)) {
            throw new IOException("ISSUE68_O0_REAPPROVAL_REQUIRED");
        }
        budget = Issue68Budget.startO0Live(root.resolve("budget.jsonl"), approvedHash);
        try {
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            durableNew(directory.resolve("approval-manifest.json"), JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(manifest()));
            forceDirectory(root);
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    public static Map<String, Object> manifest() throws IOException {
        Issue68GateV2.requireFrozen();
        Map<String, String> hashes = new TreeMap<>();
        for (String directory : java.util.List.of("src/test/java/com/bobfull/chat/infrastructure/ai",
                "src/test/java/com/bobfull/chat/infrastructure/kafka")) {
            try (var files = Files.list(Path.of(directory))) {
                for (var file : files.filter(p -> p.getFileName().toString().startsWith("Issue68")
                        && p.toString().endsWith(".java")).toList()) {
                    hashes.put(file.toString(), Issue68EvaluationContract.sha256(Files.readString(file)));
                }
            }
        }
        Map<String, Object> result = new TreeMap<>();
        result.put("approvalId", APPROVAL);
        result.put("scope", "O0_FIRST_NATIVE80_POLL80_ONLY_NOT_880_MATRIX");
        result.put("contract", new TreeMap<>(Issue68O0Plan.frozenContract()));
        result.put("sourceSha256", hashes);
        result.put("priorJournalSha256", Issue68Budget.O0_PRIOR_SHA256);
        result.put("priorUnknownNanoUsd", 19_276_800L);
        result.put("priorSettledNanoUsd", 311_100L);
        result.put("priorGrossNanoUsd", 57_907_200L);
        result.put("inputReservationCeiling", Issue68Budget.CONTEXT_INPUT_CEILING);
        result.put("inputNanoUsdPerToken", 150L);
        result.put("outputNanoUsdPerToken", 600L);
        result.put("cacheDiscountUsedForGuard", false);
        result.put("automaticRestartAllowed", false);
        result.put("nativeOrdinaryFp", "record baseline error; continue paired comparison; all scores preserved");
        result.put("stopping", "Critical/FN/review miss/technical/budget stops next HTTP; Batch newFP/category regression stops");
        return result;
    }

    public static String manifestSha256() throws IOException {
        // TreeMap의 모든 중첩 object도 순서가 고정돼 별도 JVM에서도 동일 hash다.
        return Issue68EvaluationContract.sha256(JSON.writeValueAsString(manifest()));
    }

    public synchronized Issue68HttpBoundary boundary() {
        if (closed || boundary != null) {
            throw new IllegalStateException("ISSUE68_O0_BOUNDARY_ALREADY_ISSUED");
        }
        boundary = Issue68HttpBoundary.o0Live(budget, plan, this::requireApproval);
        boundary.checkpoints(this::checkpoint);
        return boundary;
    }

    // 동일 장부/승인 상태 머신을 실제 SDK와 연결하되 loopback만 허용하는 무과금 검증 seam.
    synchronized Issue68HttpBoundary rehearsalBoundary(java.net.URI endpoint) {
        if (closed || boundary != null) {
            throw new IllegalStateException("ISSUE68_O0_BOUNDARY_ALREADY_ISSUED");
        }
        boundary = Issue68HttpBoundary.o0Preparation(endpoint, budget, plan);
        boundary.authorization(this::requireApproval);
        boundary.checkpoints(this::checkpoint);
        return boundary;
    }

    private void requireApproval() {
        try {
            if (closed || !approvedHash.equals(manifestSha256())) {
                throw new IllegalStateException("ISSUE68_O0_APPROVED_SOURCE_CHANGED");
            }
            plan.requireActive();
        } catch (IOException failure) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_O0_APPROVAL_UNAVAILABLE");
        }
    }

    public synchronized void checkpoint(String name, Object value) {
        try {
            if (closed || !name.matches("[a-z-]+")) {
                throw new IOException("ISSUE68_O0_CHECKPOINT_INVALID");
            }
            // 진행 이력은 새 파일만 추가한다. 이전 checkpoint를 덮어쓰지 않는다.
            durableNew(directory.resolve("%06d-%s.json".formatted(++checkpointSequence, name)),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                            "recordedAt", java.time.Instant.now().toString(), "value", value,
                            "stageBurdenNanoUsd", budget.stageChargedNanos(), "totalBurdenNanoUsd", budget.chargedNanos())));
        } catch (Exception failure) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_O0_CHECKPOINT_FAILED");
        }
    }

    private static void durableNew(Path path, byte[] content) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            var bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        forceDirectory(path.getParent());
    }

    private static void forceDirectory(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public Issue68Budget budget() {
        return budget;
    }

    public Issue68O0Plan plan() {
        return plan;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (boundary != null) {
            boundary.stop();
        }
        budget.close();
    }
}
