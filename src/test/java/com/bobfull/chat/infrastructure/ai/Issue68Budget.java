package com.bobfull.chat.infrastructure.ai;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

// nano-USD 정수로 전송 전 최대액을 예약한다. 기존 journal 덮어쓰기/자동 재개는 금지한다.
public final class Issue68Budget implements AutoCloseable {
    public static final long PLANNED_LIMIT = 500_000_000L;
    public static final long EXPANDED_LIMIT = 1_000_000_000L;
    public static final int CONTEXT_INPUT_CEILING = 128_000;
    private final long limit;
    private final FileChannel journal;
    private final Map<Integer, Ticket> open = new HashMap<>();
    private final Map<Integer, Long> refunds = new HashMap<>();
    private long charged;
    private long grossReserved;
    private int attempts;
    private boolean blocked;
    private FileLock lock;
    private boolean durable;
    private String digest = "GENESIS";
    public static final long O0_STAGE_LIMIT = 100_000_000L;
    public static final int O0_HTTP_LIMIT = 632; // 정상 논리 상한158 × SDK 최대4. Kafka 재전달은 별도 guard가 차단한다.
    public static final String O0_PRIOR_SHA256 = "b2fe9f54ae8441010bebeb3734896b7328d5445586fd7bc1838045d3d510cf5e";
    private boolean o0Mode;
    private boolean o0Started;
    private long stageBaselineCharge;
    private long stageBaselineGross;
    private int stageBaselineAttempts;
    private final boolean expandedMode;
    private final boolean expandedLive;
    private Issue68ExpandedPlan.Stage expandedStage;
    private final java.util.Set<Issue68ExpandedPlan.Stage> expandedCompleted = java.util.EnumSet.noneOf(Issue68ExpandedPlan.Stage.class);
    private boolean a1N1Started;
    private boolean a1N1Completed;
    private final boolean benchmarkMode;
    private boolean benchmarkStarted;
    private Issue68UsageBenchmarkPlan.Stage benchmarkStage;
    private final java.util.Set<Issue68UsageBenchmarkPlan.Stage> benchmarkCompleted = java.util.EnumSet.noneOf(Issue68UsageBenchmarkPlan.Stage.class);
    private long benchmarkBaselineCharge;
    private int benchmarkBaselineAttempts;

    public Issue68Budget(Path path, long limit) throws IOException {
        if (limit <= 0 || limit > PLANNED_LIMIT) {
            throw new IllegalArgumentException("ISSUE68_INVALID_LIMIT");
        }
        this.limit = limit;
        expandedMode = false;
        expandedLive = false;
        benchmarkMode = false;
        journal = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    // 장부 복원은 Live 재실행 승인이 아니다. 기존 미정산 예약은 해제하지 않는다.
    public static Issue68Budget restoreSmoke(Path path) throws IOException {
        return new Issue68Budget(path);
    }

    private Issue68Budget(Path path) throws IOException {
        this(path, false);
    }

    // 무과금 준비용 경계다. LiveSession/credential/HTTPS 허용 조건을 변경하거나 유료 실행을 승인하지 않는다.
    public static Issue68Budget startO0Preparation(Path path) throws IOException {
        return startO0(path, "o0-first-preparation.claim", "FAKE_PREPARATION_ONLY", "O0_PREPARATION_BEGIN");
    }

    // Live 권한은 고정 root/승인 manifest를 검증한 Session만 전달한다. 과거 기록은 append만 한다.
    static Issue68Budget startO0Live(Path path, String manifestHash) throws IOException {
        if (manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")) {
            throw new IOException("ISSUE68_O0_MANIFEST_REQUIRED");
        }
        return startO0(path, "o0-first-live.claim", manifestHash, "O0_LIVE_BEGIN");
    }

    private static Issue68Budget startO0(Path path, String claimName, String authority, String event) throws IOException {
        Issue68GateV2.requireFrozen();
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("ISSUE68_O0_PRIOR_JOURNAL_REQUIRED");
        }
        var budget = new Issue68Budget(path, true);
        try {
            if (budget.o0Started || !O0_PRIOR_SHA256.equals(Issue68EvaluationContract.sha256(Files.readString(path)))
                    || budget.attempts != 3 || budget.charged != 19_587_900L || budget.grossReserved != 57_907_200L) {
                throw new IOException("ISSUE68_O0_REAPPROVAL_OR_PRIOR_STATE");
            }
            Path claim = path.toAbsolutePath().getParent().resolve(claimName);
            try (var marker = FileChannel.open(claim, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(claim, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                ByteBuffer bytes = ByteBuffer.wrap((authority + "\n" + O0_PRIOR_SHA256 + "\n"
                        + Issue68GateV2.metadataSha256() + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    marker.write(bytes);
                }
                marker.force(true);
            }
            try (var directory = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
            String payload = new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(
                    "event", event, "attempt", budget.attempts, "nanoUsd", 0,
                    "stageLimit", O0_STAGE_LIMIT, "httpLimit", O0_HTTP_LIMIT,
                    "metadataSha256", Issue68GateV2.metadataSha256()));
            budget.appendPayload(payload);
            budget.o0Started = true;
            budget.stageBaselineCharge = budget.charged;
            budget.stageBaselineGross = budget.grossReserved;
            budget.stageBaselineAttempts = budget.attempts;
            budget.blocked = false;
            return budget;
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    // 재시작 후에는 회계만 복원한다. 새 Human 재승인 계약 없이 reserve를 재개하지 않는다.
    public static Issue68Budget auditO0(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("ISSUE68_O0_PRIOR_JOURNAL_REQUIRED");
        }
        return new Issue68Budget(path, true);
    }

    // Fake 경로는 계속 temp fixture만 받는다. Live는 별도 Session 승인으로만 연결한다.
    public static Issue68Budget startExpandedPreparation(Path path, Issue68ExpandedPlan.Stage stage,
            String manifestHash) throws IOException {
        requireTemporaryFixture(path);
        if (!Issue68ExpandedPlan.manifestSha256().equals(manifestHash)) {
            throw new IOException("ISSUE68_EXPANDED_MANIFEST_CHANGED");
        }
        return startExpanded(path, stage, manifestHash, false);
    }

    static Issue68Budget startExpandedLivePilot(Path path, String manifestHash) throws IOException {
        if (!Issue68ExpandedLiveSession.manifestSha256().equals(manifestHash)) {
            throw new IOException("ISSUE68_EXPANDED_MANIFEST_CHANGED");
        }
        return startExpanded(path, Issue68ExpandedPlan.Stage.PILOT, manifestHash, true);
    }

    // 별도 진단 승인이다. 과거 Pilot의 BEGIN/HOLD/claim은 종료하거나 삭제하지 않는다.
    static Issue68Budget startA1N1(Path path, String manifestHash) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || !Issue68A1N1Plan.manifestSha256().equals(manifestHash)) {
            throw new IOException("ISSUE68_A1_N1_PRIOR_OR_MANIFEST");
        }
        var budget = new Issue68Budget(path, false, true, true);
        try {
            if (budget.a1N1Started || !Issue68A1N1Plan.PRIOR_SHA.equals(Issue68EvaluationContract.sha256(Files.readString(path)))
                    || budget.attempts != 4 || budget.charged != 19_723_050L || budget.grossReserved != 77_184_000L
                    || !budget.open.isEmpty()) throw new IOException("ISSUE68_A1_N1_REAPPROVAL_REQUIRED");
            Path claim = path.resolveSibling("a1-native-n1.claim");
            try (var marker = FileChannel.open(claim, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(claim, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                var bytes = ByteBuffer.wrap((manifestHash + "\n" + budget.digest + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) marker.write(bytes);
                marker.force(true);
            }
            try (var directory = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) { directory.force(true); }
            budget.appendPayload(new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(
                    "event", "A1_N1_BEGIN", "attempt", budget.attempts, "nanoUsd", 0,
                    "manifestSha256", manifestHash, "priorJournalSha256", Issue68A1N1Plan.PRIOR_SHA)));
            budget.a1N1Started = true;
            budget.stageBaselineCharge = budget.charged;
            budget.stageBaselineGross = budget.grossReserved;
            budget.stageBaselineAttempts = budget.attempts;
            budget.blocked = false;
            return budget;
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    static Issue68Budget auditA1N1(Path path) throws IOException { return new Issue68Budget(path, false, true, true); }

    // 같은 고정 장부의 새 승인 scope다. 과거 Pilot/A1의 상태와 UNKNOWN을 종료·초기화하지 않는다.
    static Issue68Budget startBenchmark(Path path, Issue68UsageBenchmarkPlan.Stage stage, String hash) throws IOException {
        return startBenchmark(path, stage, hash, false);
    }

    static Issue68Budget startBenchmarkLive(Path path, Issue68UsageBenchmarkPlan.Stage stage, String hash) throws IOException {
        return startBenchmark(path, stage, hash, true);
    }

    private static Issue68Budget startBenchmark(Path path, Issue68UsageBenchmarkPlan.Stage stage, String hash, boolean live) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || !Issue68UsageBenchmarkPlan.manifestSha256(stage).equals(hash)) throw new IOException("ISSUE68_BENCHMARK_PRIOR_OR_MANIFEST");
        var budget = auditBenchmark(path);
        try {
            // 이미 완료된 실제 Burst와 동일한 장부/공개 결과여야 한다. 잠금 획득 후, claim/append 전에 검사한다.
            if (live && stage == Issue68UsageBenchmarkPlan.Stage.REMAINDER
                    && (!Issue68UsageBenchmarkPlan.COMPLETED_BURST_JOURNAL_SHA.equals(Issue68EvaluationContract.sha256(Files.readString(path)))
                    || !Issue68UsageBenchmarkPlan.COMPLETED_BURST_RESULTS_SHA.equals(Issue68EvaluationContract.sha256(
                            Files.readString(Path.of(Issue68UsageBenchmarkPlan.COMPLETED_BURST_RESULTS))))
                    || budget.attempts != 36 || budget.charged != 25_923_000L || budget.grossReserved != 700_876_800L
                    || budget.benchmarkChargedNanos() != 6_041_250L || !budget.open.isEmpty())) {
                throw new IOException("ISSUE68_BENCHMARK_COMPLETED_BURST_MISMATCH");
            }
            if (stage == Issue68UsageBenchmarkPlan.Stage.BURST
                    ? budget.benchmarkStarted || !Issue68UsageBenchmarkPlan.PRIOR_SHA.equals(Issue68EvaluationContract.sha256(Files.readString(path)))
                    : !budget.benchmarkCompleted.equals(java.util.Set.of(Issue68UsageBenchmarkPlan.Stage.BURST)) || budget.benchmarkStage != null) {
                throw new IOException("ISSUE68_BENCHMARK_REAPPROVAL_REQUIRED");
            }
            Path claim = path.resolveSibling(stage.approval() + ".claim");
            try (var marker = FileChannel.open(claim, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(claim, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                var bytes = ByteBuffer.wrap((hash + "\n" + budget.digest + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) marker.write(bytes);
                marker.force(true);
            }
            try (var parent = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) { parent.force(true); }
            budget.appendPayload(new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(
                    "event", "USAGE_BENCHMARK_BEGIN", "stage", stage.name(), "manifestSha256", hash,
                    "attempt", budget.attempts, "nanoUsd", 0, "priorJournalSha256", Issue68UsageBenchmarkPlan.PRIOR_SHA)));
            if (!budget.benchmarkStarted) {
                budget.benchmarkBaselineCharge = budget.charged; budget.benchmarkBaselineAttempts = budget.attempts;
            }
            budget.benchmarkStarted = true; budget.benchmarkStage = stage;
            budget.stageBaselineCharge = budget.charged; budget.stageBaselineGross = budget.grossReserved;
            budget.stageBaselineAttempts = budget.attempts; budget.blocked = false;
            return budget;
        } catch (Exception failure) { budget.close(); throw failure; }
    }

    public static Issue68Budget auditBenchmark(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) throw new IOException("ISSUE68_BENCHMARK_EXISTING_JOURNAL_REQUIRED");
        return new Issue68Budget(path, false, true, true, true);
    }

    synchronized void completeBenchmark(Issue68UsageBenchmarkPlan plan) {
        if (!benchmarkStarted || benchmarkStage != plan.stage() || !plan.complete() || blocked || !open.isEmpty()) {
            throw new IllegalStateException("ISSUE68_BENCHMARK_INCOMPLETE");
        }
        appendPayload(new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of("event", "USAGE_BENCHMARK_END",
                "stage", benchmarkStage.name(), "attempt", attempts, "nanoUsd", 0)));
        benchmarkCompleted.add(benchmarkStage); benchmarkStage = null; blocked = true;
    }

    public synchronized long benchmarkChargedNanos() { return benchmarkStarted ? charged - benchmarkBaselineCharge : 0; }

    synchronized void completeA1N1(Issue68A1N1Plan plan) {
        if (!a1N1Started || a1N1Completed || blocked || !open.isEmpty() || !plan.complete()) {
            throw new IllegalStateException("ISSUE68_A1_N1_INCOMPLETE");
        }
        append("A1_N1_END", attempts, 0);
        a1N1Completed = true;
        blocked = true;
    }

    private static Issue68Budget startExpanded(Path path, Issue68ExpandedPlan.Stage stage,
            String manifestHash, boolean live) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("ISSUE68_EXPANDED_PRIOR_JOURNAL_REQUIRED");
        }
        var budget = new Issue68Budget(path, false, true, live);
        try {
            boolean first = budget.expandedCompleted.isEmpty();
            if (budget.expandedStage != null || stage.ordinal() != budget.expandedCompleted.size()
                    || first && (!O0_PRIOR_SHA256.equals(Issue68EvaluationContract.sha256(Files.readString(path)))
                    || budget.attempts != 3 || budget.charged != 19_587_900L || budget.grossReserved != 57_907_200L)) {
                throw new IOException("ISSUE68_EXPANDED_REAPPROVAL_REQUIRED");
            }
            Path claim = path.resolveSibling("expanded-" + (live ? "live-" : "fake-") + stage.name() + ".claim");
            try (var marker = FileChannel.open(claim, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(claim, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                var bytes = ByteBuffer.wrap((manifestHash + "\n" + budget.digest + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) marker.write(bytes);
                marker.force(true);
            }
            try (var directory = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
            budget.appendPayload(new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(
                    "event", live ? "EXPANDED_LIVE_BEGIN" : "EXPANDED_FAKE_BEGIN", "stage", stage.name(), "attempt", budget.attempts,
                    "nanoUsd", 0, "globalLimit", EXPANDED_LIMIT, "stageLimit", stage.burdenLimit,
                    "httpLimit", stage.httpLimit, "manifestSha256", manifestHash)));
            budget.expandedStage = stage;
            budget.stageBaselineCharge = budget.charged;
            budget.stageBaselineGross = budget.grossReserved;
            budget.stageBaselineAttempts = budget.attempts;
            budget.blocked = false;
            return budget;
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    public static Issue68Budget auditExpandedPreparation(Path path) throws IOException {
        requireTemporaryFixture(path);
        return new Issue68Budget(path, false, true);
    }

    static Issue68Budget auditExpandedLiveFixture(Path path) throws IOException {
        requireTemporaryFixture(path);
        return new Issue68Budget(path, false, true, true);
    }

    private static void requireTemporaryFixture(Path path) throws IOException {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(temporary)) {
            throw new IOException("ISSUE68_EXPANDED_TEMP_FAKE_ONLY");
        }
    }

    public synchronized void completeExpandedStage(Issue68ExpandedPlan plan) {
        if (a1N1Started || !expandedMode || blocked || expandedStage != plan.stage() || !plan.complete() || !open.isEmpty()) {
            throw new IllegalStateException("ISSUE68_EXPANDED_STAGE_INCOMPLETE");
        }
        appendPayload(new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(
                "event", expandedLive ? "EXPANDED_LIVE_END" : "EXPANDED_FAKE_END",
                "stage", expandedStage.name(), "attempt", attempts, "nanoUsd", 0)));
        expandedCompleted.add(expandedStage);
        expandedStage = null;
        blocked = true; // 다음 단계는 명시적 별도 시작만 허용한다. 자동 이월·재개하지 않는다.
    }

    private Issue68Budget(Path path, boolean o0Mode) throws IOException {
        this(path, o0Mode, false);
    }

    private Issue68Budget(Path path, boolean o0Mode, boolean expandedMode) throws IOException {
        this(path, o0Mode, expandedMode, false);
    }

    private Issue68Budget(Path path, boolean o0Mode, boolean expandedMode, boolean expandedLive) throws IOException {
        this(path, o0Mode, expandedMode, expandedLive, false);
    }

    private Issue68Budget(Path path, boolean o0Mode, boolean expandedMode, boolean expandedLive, boolean benchmarkMode) throws IOException {
        this.o0Mode = o0Mode;
        this.expandedMode = expandedMode;
        this.expandedLive = expandedLive;
        this.benchmarkMode = benchmarkMode;
        limit = expandedMode ? EXPANDED_LIMIT : o0Mode ? PLANNED_LIMIT : 100_000_000L;
        durable = true;
        if (Files.isSymbolicLink(path)) {
            throw new IOException("ISSUE68_JOURNAL_SYMLINK");
        }
        journal = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            lock = journal.tryLock();
            if (lock == null) {
                throw new IOException("ISSUE68_EXECUTION_LOCKED");
            }
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            String text = Files.readString(path);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                throw new IOException("ISSUE68_JOURNAL_CORRUPT");
            }
            Map<Integer, Long> pending = new HashMap<>();
            StringBuilder prefix = new StringBuilder();
            for (String line : text.lines().toList()) {
                var root = new tools.jackson.databind.json.JsonMapper().readTree(line);
                String payload = root.path("payload").asText();
                String next = Issue68EvaluationContract.sha256(digest + "|" + payload);
                if (!next.equals(root.path("sha256").asText())) {
                    throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                }
                var event = new tools.jackson.databind.json.JsonMapper().readTree(payload);
                int number = event.path("attempt").asInt(-1);
                long amount = event.path("nanoUsd").asLong(-1);
                if (amount < 0) {
                    throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                }
                switch (event.path("event").asText()) {
                    case "A1_N1_BEGIN" -> {
                        if (!expandedMode || !expandedLive || a1N1Started || number != attempts || amount != 0
                                || !pending.isEmpty() || charged != 19_723_050L || grossReserved != 77_184_000L || attempts != 4
                                || !Issue68A1N1Plan.PRIOR_SHA.equals(Issue68EvaluationContract.sha256(prefix.toString()))
                                || !Issue68A1N1Plan.PRIOR_SHA.equals(event.path("priorJournalSha256").asText())
                                || !(Issue68A1N1Plan.manifestSha256().equals(event.path("manifestSha256").asText())
                                || benchmarkMode && Issue68UsageBenchmarkPlan.HISTORICAL_A1_MANIFEST.equals(event.path("manifestSha256").asText()))) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        a1N1Started = true;
                        stageBaselineCharge = charged;
                        stageBaselineGross = grossReserved;
                        stageBaselineAttempts = attempts;
                    }
                    case "A1_N1_END" -> {
                        if (!a1N1Started || a1N1Completed || !pending.isEmpty() || number != attempts || amount != 0) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        a1N1Completed = true;
                    }
                    case "USAGE_BENCHMARK_BEGIN" -> {
                        var stage = Issue68UsageBenchmarkPlan.Stage.valueOf(event.path("stage").asText());
                        if (!benchmarkMode || benchmarkStage != null || !pending.isEmpty() || number != attempts || amount != 0
                                || !event.path("manifestSha256").asText().matches("[0-9a-f]{64}")
                                || !Issue68UsageBenchmarkPlan.PRIOR_SHA.equals(event.path("priorJournalSha256").asText())
                                || stage == Issue68UsageBenchmarkPlan.Stage.BURST && (benchmarkStarted || attempts != 5
                                || charged != 19_881_750L || grossReserved != 96_460_800L
                                || !Issue68UsageBenchmarkPlan.PRIOR_SHA.equals(Issue68EvaluationContract.sha256(prefix.toString())))
                                || stage == Issue68UsageBenchmarkPlan.Stage.REMAINDER
                                && !benchmarkCompleted.equals(java.util.Set.of(Issue68UsageBenchmarkPlan.Stage.BURST))) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        if (!benchmarkStarted) { benchmarkBaselineCharge = charged; benchmarkBaselineAttempts = attempts; }
                        benchmarkStarted = true; benchmarkStage = stage;
                        stageBaselineCharge = charged; stageBaselineGross = grossReserved; stageBaselineAttempts = attempts;
                    }
                    case "USAGE_BENCHMARK_END" -> {
                        if (!benchmarkMode || benchmarkStage == null || !pending.isEmpty() || number != attempts || amount != 0
                                || !benchmarkStage.name().equals(event.path("stage").asText())) throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        benchmarkCompleted.add(benchmarkStage); benchmarkStage = null;
                    }
                    case "EXPANDED_FAKE_BEGIN", "EXPANDED_LIVE_BEGIN" -> {
                        var stage = Issue68ExpandedPlan.Stage.valueOf(event.path("stage").asText());
                        if (!expandedMode || expandedLive != event.path("event").asText().equals("EXPANDED_LIVE_BEGIN")
                                || expandedLive && stage != Issue68ExpandedPlan.Stage.PILOT
                                || expandedStage != null || stage.ordinal() != expandedCompleted.size()
                                || number != attempts || amount != 0 || event.path("globalLimit").asLong() != EXPANDED_LIMIT
                                || event.path("stageLimit").asLong() != stage.burdenLimit
                                || event.path("httpLimit").asInt() != stage.httpLimit
                                || !event.path("manifestSha256").asText().matches("[0-9a-f]{64}")) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        expandedStage = stage;
                        stageBaselineCharge = charged;
                        stageBaselineGross = grossReserved;
                        stageBaselineAttempts = attempts;
                    }
                    case "EXPANDED_FAKE_END", "EXPANDED_LIVE_END" -> {
                        if (!expandedMode || expandedLive != event.path("event").asText().equals("EXPANDED_LIVE_END")
                                || expandedStage == null || number != attempts || amount != 0
                                || !expandedStage.name().equals(event.path("stage").asText()) || !pending.isEmpty()) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        expandedCompleted.add(expandedStage);
                        expandedStage = null;
                    }
                    case "O0_PREPARATION_BEGIN", "O0_LIVE_BEGIN" -> {
                        if (!o0Mode || o0Started || number != attempts || amount != 0
                                || event.path("stageLimit").asLong() != O0_STAGE_LIMIT
                                || event.path("httpLimit").asInt() != O0_HTTP_LIMIT
                                || !event.path("metadataSha256").asText().matches("[0-9a-f]{64}")) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        o0Started = true;
                        stageBaselineCharge = charged;
                        stageBaselineGross = grossReserved;
                        stageBaselineAttempts = attempts;
                    }
                    case "RESERVE" -> {
                        if (number != attempts + 1 || amount < cost(CONTEXT_INPUT_CEILING, 1)
                                || amount > cost(CONTEXT_INPUT_CEILING, 1280)
                                || !benchmarkStarted && a1N1Started && (a1N1Completed || amount != cost(CONTEXT_INPUT_CEILING, 128))
                                || benchmarkStarted && benchmarkStage == null) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        pending.put(number, amount);
                        charged = Math.addExact(charged, amount);
                        grossReserved = Math.addExact(grossReserved, amount);
                        attempts++;
                    }
                    case "SETTLE", "UNKNOWN_KEEP" -> {
                        Long reserved = pending.remove(number);
                        if (reserved == null || amount > reserved
                                || event.path("event").asText().equals("UNKNOWN_KEEP") && amount != reserved) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        charged -= reserved - amount;
                        if (event.path("event").asText().equals("SETTLE")) {
                            refunds.put(number, reserved - amount);
                        }
                    }
                    case "DISPUTED_KEEP" -> {
                        Long refund = refunds.remove(number);
                        if (!(o0Mode && o0Started || expandedMode && expandedStage != null)
                                || number <= stageBaselineAttempts || refund == null || refund != amount) {
                            throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                        }
                        charged = Math.addExact(charged, amount);
                    }
                    default -> throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                }
                if (charged > (expandedMode ? EXPANDED_LIMIT : PLANNED_LIMIT)) {
                    throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                }
                if (o0Started && (charged - stageBaselineCharge > O0_STAGE_LIMIT
                        || charged < stageBaselineCharge || attempts - stageBaselineAttempts > O0_HTTP_LIMIT)) {
                    throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                }
                if (!benchmarkStarted && expandedStage != null && (charged - stageBaselineCharge > (a1N1Started ? Issue68A1N1Plan.LIMIT : expandedStage.burdenLimit)
                        || charged < stageBaselineCharge || attempts - stageBaselineAttempts > (a1N1Started ? Issue68A1N1Plan.HTTP_LIMIT : expandedStage.httpLimit))) {
                    throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                }
                if (benchmarkStarted && (charged - benchmarkBaselineCharge > Issue68UsageBenchmarkPlan.LIMIT
                        || charged < benchmarkBaselineCharge || attempts - benchmarkBaselineAttempts > Issue68UsageBenchmarkPlan.HTTP_LIMIT
                        || benchmarkStage != null && (charged - stageBaselineCharge > benchmarkStage.burdenLimit
                        || attempts - stageBaselineAttempts > benchmarkStage.httpLimit))) throw new IOException("ISSUE68_JOURNAL_CORRUPT");
                digest = next;
                prefix.append(line).append('\n');
            }
            journal.position(journal.size());
            blocked = o0Mode || expandedMode || charged > limit; // 재시작은 자동 전송 재개가 아니다.
        } catch (Exception failure) {
            journal.close();
            throw new IOException("ISSUE68_JOURNAL_UNAVAILABLE", failure);
        }
    }

    public record Usage(long input, long output, long total, Long cached) {
    }

    public record Ticket(int number, int cap, long reserved) {
    }

    public static long cost(long input, long output) {
        if (input < 0 || output < 0) {
            throw new IllegalArgumentException("ISSUE68_NEGATIVE_USAGE");
        }
        return Math.addExact(Math.multiplyExact(input, 150L), Math.multiplyExact(output, 600L));
    }

    public synchronized Ticket reserve(int cap) {
        if (blocked || cap < 1 || cap > 1280 || attempts >= 12_000) {
            blocked = true;
            throw new IllegalStateException("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        if (!benchmarkStarted && a1N1Started && (a1N1Completed || cap != 128 || stageAttempts() >= Issue68A1N1Plan.HTTP_LIMIT)) {
            blocked = true;
            throw new IllegalStateException("ISSUE68_A1_N1_BUDGET_SCOPE");
        }
        long reservation = cost(CONTEXT_INPUT_CEILING, cap);
        if (reservation > limit - charged
                || o0Mode && (!o0Started || reservation > O0_STAGE_LIMIT - stageChargedNanos()
                        || attempts - stageBaselineAttempts >= O0_HTTP_LIMIT)
                || !benchmarkStarted && expandedMode && (expandedStage == null || reservation > expandedStage.burdenLimit - stageChargedNanos()
                        || attempts - stageBaselineAttempts >= expandedStage.httpLimit)
                || benchmarkStarted && (benchmarkStage == null || reservation > Issue68UsageBenchmarkPlan.LIMIT - benchmarkChargedNanos()
                        || reservation > benchmarkStage.burdenLimit - stageChargedNanos()
                        || attempts - benchmarkBaselineAttempts >= Issue68UsageBenchmarkPlan.HTTP_LIMIT
                        || attempts - stageBaselineAttempts >= benchmarkStage.httpLimit)
                || durable && !o0Mode && !expandedMode && reservation > 100_000_000L - grossReserved) {
            blocked = true;
            throw new IllegalStateException("ISSUE68_BUDGET_LIMIT");
        }
        Ticket ticket = new Ticket(attempts + 1, cap, reservation);
        append("RESERVE", ticket.number(), reservation);
        charged += reservation;
        grossReserved += reservation;
        attempts++;
        open.put(ticket.number(), ticket);
        return ticket;
    }

    public synchronized void settle(Ticket ticket, Usage usage) {
        Ticket stored = open.get(ticket.number());
        if (!ticket.equals(stored)) {
            blocked = true;
            throw new IllegalStateException("ISSUE68_UNKNOWN_TICKET");
        }
        long reserved = ticket.reserved();
        // Missing/malformed Usage never releases a reservation. Even HTTP200 is not zero-cost.
        if (!validUsage(usage, ticket.cap())) {
            append("UNKNOWN_KEEP", ticket.number(), reserved);
            open.remove(ticket.number());
            if (expandedMode) blocked = true;
            return;
        }
        long actual = cost(usage.input(), usage.output());
        append("SETTLE", ticket.number(), actual);
        charged -= reserved - actual;
        refunds.put(ticket.number(), reserved - actual);
        open.remove(ticket.number());
    }

    // HTTP/SDK Usage가 충돌하면 먼저 환급한 차액을 다시 부담으로 유지한다. gross/attempt는 늘리지 않는다.
    synchronized void retainDisputed(int number) {
        Long refund = refunds.get(number);
        if (!(o0Mode && o0Started || expandedMode && expandedStage != null || benchmarkStage != null)
                || number <= stageBaselineAttempts || refund == null || refund > limit - charged
                || refund > (benchmarkStage != null ? benchmarkStage.burdenLimit : expandedMode ? expandedStage.burdenLimit : O0_STAGE_LIMIT) - stageChargedNanos()
                || benchmarkStarted && refund > Issue68UsageBenchmarkPlan.LIMIT - benchmarkChargedNanos()) {
            blocked = true;
            throw new IllegalStateException("ISSUE68_DISPUTED_USAGE_HOLD");
        }
        append("DISPUTED_KEEP", number, refund);
        charged += refund;
        refunds.remove(number);
        blocked = true;
    }

    static boolean validUsage(Usage usage, int cap) {
        return usage != null && usage.input() >= 0 && usage.input() <= CONTEXT_INPUT_CEILING
                && usage.output() >= 0 && usage.output() <= cap && usage.total() == usage.input() + usage.output()
                && (usage.cached() == null || usage.cached() >= 0 && usage.cached() <= usage.input());
    }

    private void append(String event, int number, long amount) {
        String payload = "{\"event\":\"" + event + "\",\"attempt\":" + number + ",\"nanoUsd\":" + amount + "}";
        appendPayload(payload);
    }

    private void appendPayload(String payload) {
        String next = Issue68EvaluationContract.sha256(digest + "|" + payload);
        String line = durable ? new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                Map.of("payload", payload, "sha256", next)) + "\n" : payload + "\n";
        try {
            ByteBuffer bytes = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                journal.write(bytes);
            }
            journal.force(true); // 예약 영속화 실패 시 HTTP를 전송하지 않는다.
            digest = next;
        } catch (IOException failure) {
            blocked = true;
            throw new IllegalStateException("ISSUE68_JOURNAL_FAILED", failure);
        }
    }

    public synchronized long chargedNanos() {
        return charged;
    }

    public synchronized int attempts() {
        return attempts;
    }

    public synchronized long grossReservedNanos() {
        return grossReserved;
    }

    public synchronized boolean blocked() {
        return blocked;
    }

    public synchronized long stageChargedNanos() {
        return benchmarkStarted || o0Started || expandedStage != null || !expandedCompleted.isEmpty() ? charged - stageBaselineCharge : 0;
    }

    public synchronized long stageGrossReservedNanos() {
        return benchmarkStarted || o0Started || expandedStage != null || !expandedCompleted.isEmpty() ? grossReserved - stageBaselineGross : 0;
    }

    public synchronized int stageAttempts() {
        return benchmarkStarted || o0Started || expandedStage != null || !expandedCompleted.isEmpty() ? attempts - stageBaselineAttempts : 0;
    }

    @Override
    public synchronized void close() throws IOException {
        blocked = true;
        journal.close();
    }
}
