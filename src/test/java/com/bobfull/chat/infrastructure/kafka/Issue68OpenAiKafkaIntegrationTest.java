package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.infrastructure.ai.Issue68Budget;
import com.bobfull.chat.infrastructure.ai.Issue68EvaluationContract;
import com.bobfull.chat.infrastructure.ai.Issue68FakeOpenAiEndpoint;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.port.AiModerationPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class Issue68OpenAiKafkaIntegrationTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    @TempDir
    Path temporary;

    @Test
    void 순차_진단20은_양쪽_Native_20회이며_MySQL_commit과_Offset을_확인한다() throws Exception {
        // given
        List<Map<String, Object>> observations = new ArrayList<>();
        var cases = Issue68EvaluationContract.diagnostic();
        Issue68OpenAiKafkaHarness.Result nativeResult = null;
        // when
        for (boolean batch : List.of(false, true)) {
            try (var endpoint = new Issue68FakeOpenAiEndpoint();
                    var budget = new Issue68Budget(temporary.resolve("sequential-" + batch + ".jsonl"), Issue68Budget.PLANNED_LIMIT);
                    var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
                var result = execute(batch, cases, true, endpoint, boundary);
                // then
                assertThat(result.samples()).hasSize(20).allMatch(s -> s.completed()
                        && s.receivedNs() <= s.flushNs() && s.flushNs() <= s.committedNs()
                        && s.committedNs() <= s.returnedNs());
                assertThat(result.finalRows()).isEqualTo(20);
                assertThat(result.finalLag()).isZero();
                assertThat(result.transactionViolations()).isZero();
                assertThat(result.unsafeCommits()).isZero();
                assertThat(result.commitObservations()).isPositive();
                assertThat(result.gateReasons()).isEmpty();
                assertThat(boundary.calls()).hasSize(20).allMatch(c -> !c.batch && c.caseIds.size() == 1);
                assertThat(endpoint.received()).isEqualTo(20);
                if (!batch) {
                    nativeResult = result;
                } else {
                    Issue68OpenAiKafkaHarness.requireNextStage(nativeResult, result, cases);
                }
                observations.add(Map.of("result", result, "requests", boundary.calls(), "http", boundary.attempts(),
                        "reservedOrSettledNanoUsd", budget.chargedNanos()));
            }
        }
        write("underfill", observations);
    }

    @Test
    void Pilot은_실제_Batch_요청을_만들고_개별_DB의_Token은_복제하지_않는다() throws Exception {
        // given
        var cases = Issue68EvaluationContract.diagnostic();
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("pilot.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            // when
            var result = execute(true, cases, false, endpoint, boundary);
            // then
            assertThat(boundary.calls()).anyMatch(c -> c.batch && c.caseIds.size() >= 2);
            assertThat(boundary.calls().stream().mapToInt(c -> c.caseIds.size()).sum()).isEqualTo(20);
            assertThat(result.samples()).hasSize(20).allMatch(Issue68OpenAiKafkaHarness.Sample::completed);
            var batchIds = boundary.calls().stream().filter(c -> c.batch).flatMap(c -> c.caseIds.stream()).toList();
            assertThat(result.samples().stream().filter(s -> batchIds.contains(s.caseId())))
                    .allMatch(s -> s.promptTokens() == null && s.totalTokens() == null);
            assertThat(result.finalLag()).isZero();
            assertThat(result.dltRecords()).isZero();
            assertThat(result.unsafeCommits()).isZero();
            assertThat(boundary.attempts()).hasSize(boundary.calls().size());
            assertThat(boundary.calls()).allMatch(c -> c.requestSha256 != null && c.inputEstimate > 0);
            write("pilot", Map.of("result", result, "requests", boundary.calls(), "http", boundary.attempts(),
                    "reservedOrSettledNanoUsd", budget.chargedNanos()));
        }
    }

    @Test
    void SDK_429후_성공은_Kafka_재전달없이_HTTP만_증가한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("sdk.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.RATE_LIMIT, Issue68FakeOpenAiEndpoint.Mode.OK);
            // when
            var result = execute(false, Issue68EvaluationContract.diagnostic().subList(0, 1), true, endpoint, boundary);
            // then
            assertThat(boundary.calls()).hasSize(1);
            assertThat(boundary.attempts()).hasSize(2);
            assertThat(result.samples().get(0).deliveries()).isEqualTo(1);
            assertThat(result.dltRecords()).isZero();
            assertThat(result.finalLag()).isZero();
            assertThat(result.gateReasons()).contains("USAGE_UNKNOWN");
        }
    }

    @Test
    void 전체_매핑실패는_그룹3회후_DLT이며_다음평가_진행을_차단한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("mapping.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.MAPPING);
            // 각 partition에 2건씩 배치되어 실제 Batch가 생성된다.
            var cases = Issue68EvaluationContract.diagnostic().subList(0, 6);
            // when
            var result = execute(true, cases, false, endpoint, boundary);
            // then
            assertThat(boundary.calls()).allMatch(c -> c.batch);
            for (var c : cases) {
                assertThat(boundary.calls().stream().filter(call -> call.caseIds.contains(c.id())).count()).isEqualTo(3);
            }
            assertThat(result.samples()).hasSize(6).allMatch(s -> !s.completed() && s.status().equals("ANALYSIS_FAILED"));
            assertThat(result.dltRecords()).isEqualTo(6);
            assertThat(result.finalLag()).isZero();
            assertThat(result.unsafeCommits()).isZero();
            assertThatThrownBy(() -> Issue68OpenAiKafkaHarness.requireNextStage(result, result, cases))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("HUMAN_REVIEW_REQUIRED");
            write("mapping-failure", Map.of("result", result, "requests", boundary.calls(), "http", boundary.attempts()));
        }
    }

    @Test
    void Validation_실패는_정상판정이_아니며_실제_DLT와_최종_DB를_관측한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("validation.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.VALIDATION);
            // when
            var result = execute(false, Issue68EvaluationContract.diagnostic().subList(0, 1), true, endpoint, boundary);
            // then
            assertThat(boundary.calls()).hasSize(3);
            assertThat(result.samples().get(0).completed()).isFalse();
            assertThat(result.dltRecords()).isEqualTo(1);
            assertThat(result.gateReasons()).contains("UNRESOLVED");
            assertThat(result.finalLag()).isZero();
            assertThat(result.unsafeCommits()).isZero();
        }
    }

    @Test
    void Provider_전체실패_소진뒤_DB_첫실패는_Recovery만_재시도한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("recovery.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.SERVER_ERROR);
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            var cases = Issue68EvaluationContract.diagnostic().subList(0, 2);
            // when: source 3partition 중 하나에 2건을 prefill해 공유 장애 요청 하나를 고정한다.
            String jdbc = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68?serverTimezone=UTC";
            var result = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(), jdbc, "root", "issue68-fixture-only",
                    true, cases, false, boundary, endpoint.uri().toString(), context -> {
                        var repository = context.getBean(com.bobfull.chat.infrastructure.repository.ChatModerationRepository.class);
                        var advised = (org.springframework.aop.framework.Advised) repository;
                        int index = 0;
                        for (int i = 0; i < advised.getAdvisors().length; i++) {
                            if (advised.getAdvisors()[i].getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor) {
                                index = i + 1;
                            }
                        }
                        advised.addAdvice(index, (org.aopalliance.intercept.MethodInterceptor) call -> {
                            if (call.getMethod().getName().equals("saveAndFlush")
                                    && call.getArguments()[0] instanceof com.bobfull.chat.domain.entity.ChatModeration row
                                    && row.getStatus() == com.bobfull.chat.domain.entity.ModerationProcessingStatus.ANALYSIS_FAILED
                                    && first.getAndSet(false)) {
                                throw new org.springframework.dao.TransientDataAccessResourceException("issue68-fixture-save");
                            }
                            return call.proceed();
                        });
                    }, 1);
            // then: 실제 SDK4 × Kafka3. 최종 DB 실패 이후 추가 Provider 분석은 없다.
            for (var c : cases) {
                assertThat(boundary.calls().stream().filter(call -> call.caseIds.contains(c.id())).count()).isEqualTo(3);
            }
            assertThat(boundary.attempts()).hasSize(boundary.calls().size() * 4);
            assertThat(endpoint.received()).isEqualTo(boundary.attempts().size());
            assertThat(result.saveFailures()).isEqualTo(1);
            assertThat(result.dltRecords()).isEqualTo(3);
            assertThat(result.finalRows()).isEqualTo(2);
            assertThat(boundary.calls()).hasSize(3).allMatch(c -> c.batch && c.caseIds.size() == 2);
            assertThat(result.samples()).allMatch(s -> s.status().equals("ANALYSIS_FAILED"));
            assertThat(result.finalLag()).isZero();
            assertThat(result.unsafeCommits()).isZero();
            assertThat(budget.chargedNanos()).isLessThanOrEqualTo(Issue68Budget.PLANNED_LIMIT);
            write("recovery-only", Map.of("result", result, "requests", boundary.calls(), "http", boundary.attempts(),
                    "reservedOrSettledNanoUsd", budget.chargedNanos()));
        }
    }

    @Test
    void Parse_실패는_UNRESOLVED로_남고_Offset과_DLT를_별도_확인한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("parse.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.PARSE);
            // when
            var result = execute(false, Issue68EvaluationContract.diagnostic().subList(0, 1), true, endpoint, boundary);
            // then
            assertThat(result.quality().unresolved()).isEqualTo(1);
            assertThat(result.quality().tn()).isZero();
            assertThat(result.dltRecords()).isEqualTo(1);
            assertThat(result.finalLag()).isZero();
        }
    }

    @Test
    void 정상_응답의_FN은_저장돼도_품질_Gate에서_멈추고_Retry로_숨기지_않는다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("semantic.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.SEMANTIC_SAFE);
            var cases = Issue68EvaluationContract.diagnostic().stream()
                    .filter(c -> c.id().equals("HOLDOUT-PROFANITY-16")).toList();
            // when
            var result = execute(false, cases, true, endpoint, boundary);
            // then
            assertThat(result.samples().get(0).status()).isEqualTo("SAFE");
            assertThat(result.quality().fn()).isEqualTo(1);
            assertThat(result.gateReasons()).contains("FN", "GT_HIGH_DOWNGRADE");
            assertThat(boundary.calls()).hasSize(1);
            assertThat(result.dltRecords()).isZero();
            assertThatThrownBy(() -> Issue68OpenAiKafkaHarness.requireNextStage(result, result, cases))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("HUMAN_REVIEW_REQUIRED");
        }
    }

    @Test
    void 전체80은_공유_장부로_두_모드를_실행하고_Rule과_Batch를_구분한다() throws Exception {
        // given: 단일 wallet/transport를 두 Context에 걸쳐 재사용한다. 단계 전환으로 예산을 초기화하지 않는다.
        var cases = Issue68EvaluationContract.ordered(55);
        List<Map<String, Object>> observations = new ArrayList<>();
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("shared-wallet.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            Issue68OpenAiKafkaHarness.Result nativeResult = null;
            long priorCharge = 0;
            // when
            for (boolean batch : List.of(false, true)) {
                int before = boundary.calls().size();
                var result = execute(batch, cases, false, endpoint, boundary);
                var calls = boundary.calls().subList(before, boundary.calls().size());
                // then
                assertThat(result.finalRows()).isEqualTo(80);
                assertThat(result.finalLag()).isZero();
                assertThat(result.quality().resultExact()).isEqualTo(80); // GT echo fixture, 모델 품질 결과가 아니다.
                assertThat(result.gateReasons()).isEmpty();
                assertThat(calls.stream().mapToInt(c -> c.caseIds.size()).sum()).isEqualTo(79);
                assertThat(calls).noneMatch(c -> c.caseIds.contains("HOLDOUT-PI-01"));
                assertThat(budget.chargedNanos()).isGreaterThan(priorCharge);
                priorCharge = budget.chargedNanos();
                if (!batch) {
                    assertThat(calls).hasSize(79).allMatch(c -> !c.batch);
                    nativeResult = result;
                } else {
                    assertThat(calls).anyMatch(c -> c.batch);
                    Issue68OpenAiKafkaHarness.requireNextStage(nativeResult, result, cases);
                }
                observations.add(Map.of("result", result, "requests", List.copyOf(calls),
                        "cumulativeReservedOrSettledNanoUsd", budget.chargedNanos()));
            }
            assertThat(budget.attempts()).isEqualTo(endpoint.received());
            write("heldout80-shared-wallet", Map.of("runs", observations, "http", boundary.attempts()));
        }
    }

    @Test
    void 매핑오류가_재처리로_회복돼도_다음_품질평가로_자동_진행하지_않는다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("recovered-mapping.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.MAPPING, Issue68FakeOpenAiEndpoint.Mode.OK);
            var cases = Issue68EvaluationContract.diagnostic().subList(0, 6);
            // when
            var result = execute(true, cases, false, endpoint, boundary);
            // then
            assertThat(result.samples()).allMatch(Issue68OpenAiKafkaHarness.Sample::completed);
            assertThat(result.finalLag()).isZero();
            assertThat(result.dltRecords()).isZero();
            assertThat(result.gateReasons()).contains("TECHNICAL_FAILURE_OBSERVED");
            assertThatThrownBy(() -> Issue68OpenAiKafkaHarness.requireNextStage(result, result, cases))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("HUMAN_REVIEW_REQUIRED");
        }
    }

    private static Issue68OpenAiKafkaHarness.Result execute(boolean batch, List<Issue68EvaluationContract.Case> cases,
            boolean sequential, Issue68FakeOpenAiEndpoint endpoint, Issue68HttpBoundary boundary) {
        String jdbc = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68?serverTimezone=UTC";
        return Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(), jdbc, "root", "issue68-fixture-only",
                batch, cases, sequential, boundary, endpoint.uri().toString(), context -> {
                    assertThat(context.getBeansOfType(AiModerationPort.class)).hasSize(1);
                    assertThat(context.getBeansOfType(AiModerationBatchPort.class)).hasSize(batch ? 1 : 0);
                    assertThat(context.getBean(OpenAiCommonProperties.class).getMaxRetries()).isEqualTo(3);
                });
    }

    @Test
    void 최소_Smoke_Native1_Batch2는_공유_십센트_장부와_부분결과를_유지한다() throws Exception {
        // given
        var cases = com.bobfull.chat.infrastructure.ai.Issue68LiveSession.smokeCases();
        var checkpoints = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = Issue68Budget.restoreSmoke(temporary.resolve("smoke-wallet.jsonl"));
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            boundary.checkpoints(checkpoints::put);
            // when
            var nativeResult = execute(false, cases.subList(0, 1), false, endpoint, boundary);
            String jdbc = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68?serverTimezone=UTC";
            var batchResult = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(), jdbc, "root", "issue68-fixture-only",
                    true, cases, false, boundary, endpoint.uri().toString(), context -> { }, 1);
            // then
            assertThat(boundary.calls()).hasSize(2);
            assertThat(boundary.calls().get(0).batch).isFalse();
            assertThat(boundary.calls().get(1).batch).isTrue();
            assertThat(boundary.calls().get(1).caseIds).hasSize(2);
            boundary.calls().forEach(call -> com.bobfull.chat.infrastructure.ai.Issue68LiveSession
                    .verifyFrozenRequest(call.batch, call.requestSha256));
            assertThat(nativeResult.gateReasons()).isEmpty();
            assertThat(batchResult.gateReasons()).isEmpty();
            assertThat(batchResult.finalRows()).isEqualTo(2);
            assertThat(batchResult.finalLag()).isZero();
            assertThat(batchResult.unsafeCommits()).isZero();
            assertThat(checkpoints).containsKeys("http-reserved", "http-completed", "partial-native", "partial-batch");
            assertThat(budget.grossReservedNanos()).isLessThanOrEqualTo(100_000_000L);
            write("live-smoke-rehearsal", Map.of("native", nativeResult, "batch", batchResult,
                    "requests", boundary.calls(), "http", boundary.attempts(),
                    "grossReservedNanoUsd", budget.grossReservedNanos()));
        }
    }

    private static void write(String name, Object value) throws Exception {
        Path directory = Path.of("build/issue68");
        Files.createDirectories(directory);
        Map<String, String> hashes = new java.util.TreeMap<>();
        for (String path : List.of("src/test/java/com/bobfull/chat/infrastructure/ai",
                "src/test/java/com/bobfull/chat/infrastructure/kafka")) {
            try (var files = Files.list(Path.of(path))) {
                for (Path file : files.filter(p -> p.getFileName().toString().startsWith("Issue68")
                        && p.toString().endsWith(".java")).toList()) {
                    hashes.put(file.toString(), Issue68EvaluationContract.sha256(Files.readString(file)));
                }
            }
        }
        String head;
        String state;
        var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        head = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (git.waitFor() != 0) {
            throw new IllegalStateException("ISSUE68_HEAD_UNKNOWN");
        }
        var status = new ProcessBuilder("git", "status", "--porcelain").start();
        state = status.getInputStream().readAllBytes().length == 0 ? "clean" : "uncommitted-source-snapshot";
        if (status.waitFor() != 0) {
            throw new IllegalStateException("ISSUE68_STATE_UNKNOWN");
        }
        // 무과금 fixture 결과이며 유료 결과로 재사용하지 않는다. 원문/키/DB id를 저장하지 않는다.
        Path output = directory.resolve("fake-" + name + "-" + java.time.Instant.now().toEpochMilli()
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".json");
        Files.writeString(output, new JsonMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("scope", "FAKE_ENDPOINT_MYSQL_KAFKA_NOT_OPENAI_QUALITY",
                        "groundTruthSha256", Issue68EvaluationContract.groundTruthSha256(), "data", value,
                        "recordedAt", java.time.Instant.now().toString(), "executionHead", head,
                        "executionState", state, "sourceHashes", hashes,
                        "gateVersion", Issue68EvaluationContract.GATE_VERSION,
                        "provenance", Issue68EvaluationContract.PROVENANCE)), java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("ISSUE68_RAW_PATH=" + output);
    }

    @Test
    void gzip_응답의_Native와_Batch는_Usage_정산후_DB와_Offset이_정상_수렴한다() throws Exception {
        // given
        var cases = com.bobfull.chat.infrastructure.ai.Issue68LiveSession.smokeCases();
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = Issue68Budget.restoreSmoke(temporary.resolve("gzip-wallet.jsonl"));
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.gzip(true);
            var sdk = new com.bobfull.chat.infrastructure.ai.Issue68SdkUsage(boundary);
            // when
            String jdbc = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68?serverTimezone=UTC";
            var nativeResult = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(), jdbc, "root", "issue68-fixture-only",
                    false, cases.subList(0, 1), false, boundary, endpoint.uri().toString(), context -> context
                            .getBean(io.micrometer.observation.ObservationRegistry.class)
                            .observationConfig().observationHandler(sdk), 1);
            assertThat(sdk.matches(1)).isTrue();
            var batchResult = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(), jdbc, "root", "issue68-fixture-only",
                    true, cases, false, boundary, endpoint.uri().toString(), context -> context
                            .getBean(io.micrometer.observation.ObservationRegistry.class)
                            .observationConfig().observationHandler(sdk), 1);
            // then
            assertThat(sdk.matches(2)).isTrue();
            assertThat(sdk.entries()).hasSize(2);
            assertThat(boundary.calls()).hasSize(2);
            assertThat(boundary.attempts()).hasSize(2).allMatch(a -> a.usage() != null
                    && a.contentEncoding().equals("gzip") && a.settlement().equals("SETTLED"));
            assertThat(nativeResult.gateReasons()).isEmpty();
            assertThat(batchResult.gateReasons()).isEmpty();
            for (var result : List.of(nativeResult, batchResult)) {
                assertThat(result.finalLag()).isZero();
                assertThat(result.transactionViolations()).isZero();
                assertThat(result.unsafeCommits()).isZero();
                assertThat(result.dltRecords()).isZero();
                assertThat(result.commitObservations()).isPositive();
                assertThat(result.samples()).allMatch(s -> s.completed() && s.committedNs() >= s.flushNs());
            }
            assertThat(nativeResult.samples().get(0).promptTokens()).isEqualTo(900L);
            assertThat(nativeResult.samples().get(0).totalTokens()).isEqualTo(920L);
            assertThat(batchResult.samples()).hasSize(2).allMatch(s -> s.promptTokens() == null && s.totalTokens() == null);
            assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(1800, 60));
            assertThat(budget.grossReservedNanos()).isEqualTo(38_630_400L);
            write("usage-gzip-roundtrip", Map.of("native", nativeResult, "batch", batchResult,
                    "requests", boundary.calls(), "http", boundary.attempts(),
                    "chargedNanoUsd", budget.chargedNanos(), "grossReservedNanoUsd", budget.grossReservedNanos()));
        }
    }
}
