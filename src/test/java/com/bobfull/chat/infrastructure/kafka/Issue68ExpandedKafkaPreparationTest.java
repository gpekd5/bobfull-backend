package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.infrastructure.ai.Issue68Budget;
import com.bobfull.chat.infrastructure.ai.Issue68EvaluationContract;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPreparationSession;
import com.bobfull.chat.infrastructure.ai.Issue68FakeOpenAiEndpoint;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.infrastructure.ai.Issue68O0PreparationTest;
import com.bobfull.chat.infrastructure.ai.Issue68SdkUsage;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class Issue68ExpandedKafkaPreparationTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_expanded")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    private static final List<Map<String, Object>> RUNS = new CopyOnWriteArrayList<>();
    @TempDir Path temporary;

    @Test
    void Pilot120건은_여섯조건에서_실제SDK_Kafka_MySQL_Commit을_무과금으로_연결한다() throws Exception {
        // given
        Path root = fixture(Issue68ExpandedPlan.Stage.PILOT);
        try (var session = new Issue68ExpandedPreparationSession(root, Issue68ExpandedPlan.Stage.PILOT);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            endpoint.gzip(true);
            endpoint.beforeResponse(() -> java.util.concurrent.locks.LockSupport.parkNanos(60_000_000));
            int records = 0;
            for (var unit : Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == session.plan().stage()).toList()) {
                session.plan().start(unit);
                try (var boundary = session.boundary(endpoint.uri())) {
                    var sdk = new Issue68SdkUsage(boundary);
                    // when
                    var result = execute(unit, boundary, endpoint, sdk, owner -> { });
                    // then: fixture GT echo이며 모델 품질 PASS가 아니다.
                    assertComplete(result, 20);
                    assertThat(result.quality().resultExact()).isEqualTo(20);
                    assertThat(boundary.calls()).allSatisfy(call -> assertThat(sdk.matches(call.number)).isTrue());
                    assertThat(boundary.attempts()).hasSize(boundary.calls().size());
                    if (unit.mode() != Issue68ExpandedPlan.Mode.NATIVE) {
                        assertThat(boundary.calls()).anyMatch(call -> call.caseIds.size() >= 2);
                    }
                    session.plan().completeUnit(true);
                    records += result.finalRows();
                    RUNS.add(Map.of("scope", "FAKE_PILOT_NOT_LIVE_QUALITY", "unit", unit, "result", result,
                            "calls", boundary.calls(), "attempts", boundary.attempts(), "sdk", sdk.entries(),
                            "burden", session.budget().chargedNanos(), "gross", session.budget().grossReservedNanos(),
                            "ownerState", unit.mode().owner() ? lastCheckpoint(root, "owner-state") : Map.of(),
                            "partial", lastCheckpoint(root, "partial-")));
                }
            }
            assertThat(records).isEqualTo(120);
            assertThat(session.plan().complete()).isTrue();
            session.completeStage();
            assertThat(session.budget().chargedNanos()).isLessThan(Issue68Budget.EXPANDED_LIMIT);
            assertThatThrownBy(() -> session.budget().reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
    }

    @Test
    void Owner도_80건의_Rule완료와_Provider_Usage를_분리하고_Offset을_끝까지_commit한다() throws Exception {
        // given
        var unit = unit(Issue68ExpandedPlan.Stage.MAIN, Issue68ExpandedPlan.Traffic.BURST, Issue68ExpandedPlan.Mode.TIME_100);
        Path root = fixture(unit.stage());
        try (var session = new Issue68ExpandedPreparationSession(root, unit.stage());
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            prepareReferenceOnly(session.plan(), unit);
            try (var boundary = session.boundary(endpoint.uri())) {
                var sdk = new Issue68SdkUsage(boundary);
                // when
                var result = execute(unit, boundary, endpoint, sdk, owner -> { });
                // then
                assertComplete(result, 80);
                assertThat(boundary.calls().stream().flatMap(call -> call.caseIds.stream())).hasSize(79).doesNotHaveDuplicates();
                assertThat(result.samples().stream().filter(s -> "BOBFULL_RULE".equals(s.provider()))).hasSize(1);
                assertThat(result.samples()).filteredOn(s -> boundary.calls().stream().anyMatch(c -> c.batch && c.caseIds.contains(s.caseId())))
                        .allSatisfy(s -> assertThat(s.promptTokens()).isNull());
                assertThat(sdk.entries()).hasSize(boundary.calls().size());
                RUNS.add(Map.of("scope", "FAKE_OWNER_RULE_USAGE", "unit", unit, "result", result,
                        "calls", boundary.calls(), "attempts", boundary.attempts(), "ownerState", lastCheckpoint(root, "owner-state"),
                        "partial", lastCheckpoint(root, "partial-")));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Issue68ExpandedPlan.Traffic.class)
    void 네가지_발행조건은_DB완료와_무관하게_동일_80건을_발행한다(Issue68ExpandedPlan.Traffic traffic) throws Exception {
        // given
        var unit = unit(Issue68ExpandedPlan.Stage.MAIN, traffic, Issue68ExpandedPlan.Mode.NATIVE);
        Path root = fixture(unit.stage());
        try (var session = new Issue68ExpandedPreparationSession(root, unit.stage());
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            prepareReferenceOnly(session.plan(), unit);
            try (var boundary = session.boundary(endpoint.uri())) {
                var sdk = new Issue68SdkUsage(boundary);
                // when
                var result = execute(unit, boundary, endpoint, sdk, owner -> { });
                // then
                assertComplete(result, 80);
                assertThat(result.polls().stream().flatMap(List::stream)).hasSize(80);
                assertThat(boundary.calls()).hasSize(79);
                var json = new JsonMapper();
                var partial = lastPartial(root);
                assertThat(partial).isNotNull();
                var publication = json.readTree(Files.readString(partial)).path("data").path("publication");
                assertThat(publication.size()).isEqualTo(80);
                long first = publication.get(0).path("publishedNs").asLong();
                long last = publication.get(79).path("publishedNs").asLong();
                assertThat(publication.valueStream()).allSatisfy(p -> assertThat(p.path("publishedNs").asLong())
                        .isGreaterThanOrEqualTo(p.path("targetNs").asLong()));
                assertThat(last - first).isGreaterThanOrEqualTo(Duration.ofMillis(79L * traffic.intervalMs).toNanos()
                        - (first - publication.get(0).path("targetNs").asLong()));
                RUNS.add(Map.of("scope", "FAKE_PUBLICATION_CONTRACT", "unit", unit,
                        "publishSpanNs", last - first, "result", result, "partial", lastCheckpoint(root, "partial-")));
            }
        }
    }

    @Test
    void Owner_Commit실패는_DB성공을_보존하고_다음_HTTP와_다음조건을_차단한다() throws Exception {
        fault("COMMIT", null, owner -> owner.beforeCommit = offsets -> {
            throw new org.apache.kafka.clients.consumer.CommitFailedException("fixture");
        }, false);
    }

    @ParameterizedTest
    @EnumSource(value = Issue68FakeOpenAiEndpoint.Mode.class, names = {
            "NO_USAGE", "RATE_LIMIT", "RETRY_KNOWN_USAGE", "MAPPING", "VALIDATION", "PARSE", "TRUNCATED", "SEMANTIC_SAFE"})
    void Owner_Usage_Retry_응답계약_품질오류는_부분결과를_보존하고_추가전송을_막는다(
            Issue68FakeOpenAiEndpoint.Mode mode) throws Exception {
        fault(mode.name(), mode, owner -> { }, false);
    }

    @Test
    void Owner_예산소진시_SDK와_Kafka가_재시도해도_HTTP_수신은_0회다() throws Exception {
        fault("BUDGET", null, owner -> { }, true);
    }

    private void fault(String name, Issue68FakeOpenAiEndpoint.Mode mode,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> ownerSetup, boolean exhaust) throws Exception {
        // given: 이전 조건은 계약 fixture로만 준비한다. 해당 조건을 실제 측정한 것처럼 기록하지 않는다.
        var unit = unit(Issue68ExpandedPlan.Stage.PILOT, Issue68ExpandedPlan.Traffic.BURST, Issue68ExpandedPlan.Mode.TIME_100);
        Path root = fixture(unit.stage());
        try (var session = new Issue68ExpandedPreparationSession(root, unit.stage());
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            prepareReferenceOnly(session.plan(), unit);
            if (mode != null) endpoint.defaultMode(mode);
            endpoint.beforeResponse(() -> java.util.concurrent.locks.LockSupport.parkNanos(20_000_000));
            if (exhaust) {
                for (int i = 0; i < 5; i++) session.budget().settle(session.budget().reserve(1280),
                        new Issue68Budget.Usage(128000, 1280, 129280, 0L));
            }
            try (var boundary = session.boundary(endpoint.uri())) {
                var sdk = new Issue68SdkUsage(boundary);
                // when
                assertThatThrownBy(() -> execute(unit, boundary, endpoint, sdk, ownerSetup))
                        .isInstanceOf(RuntimeException.class);
                int received = endpoint.received();
                // then: 다음 논리 실행과 SDK/HTTP의 추가 전송도 허용하지 않는다.
                assertThat(session.plan().stopped()).isTrue();
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false)).isInstanceOf(IllegalStateException.class);
                assertThat(endpoint.received()).isEqualTo(received);
                assertThatThrownBy(() -> session.plan().completeUnit(true)).hasMessage("ISSUE68_EXPANDED_HOLD");
                assertThatThrownBy(session::completeStage).hasMessage("ISSUE68_EXPANDED_STAGE_INCOMPLETE");
                var partial = lastPartial(root);
                assertThat(partial).isNotNull();
                var snapshot = new JsonMapper().readTree(Files.readString(partial));
                if (name.equals("COMMIT")) {
                    assertThat(received).isEqualTo(1);
                    assertThat(snapshot.path("data").path("cases").valueStream()
                            .filter(c -> c.path("dbCommittedNs").asLong() > 0).count()).isGreaterThan(0);
                    assertThat(snapshot.path("data").path("lag").asLong()).isGreaterThan(0);
                }
                if (exhaust) assertThat(received).isZero();
                if (List.of("NO_USAGE", "RATE_LIMIT", "RETRY_KNOWN_USAGE").contains(name)) {
                    assertThat(received).isEqualTo(1);
                    assertThat(boundary.attempts()).hasSize(1);
                    assertThat(boundary.attempts().get(0).settlement()).isEqualTo("UNKNOWN_KEEP");
                    assertThat(session.budget().stageChargedNanos()).isEqualTo(boundary.attempts().get(0).reservedNanos());
                }
                RUNS.add(Map.of("scope", "FAKE_FAULT_WITH_CONTRACT_ONLY_PRIOR_UNITS", "fault", name,
                        "httpReceived", received, "attempts", boundary.attempts(), "holds", session.plan().holds(),
                        "partial", snapshot, "burden", session.budget().chargedNanos(), "ownerState", lastCheckpoint(root, "owner-state")));
            }
        }
    }

    private Issue68OpenAiKafkaHarness.Result execute(Issue68ExpandedPlan.Unit unit, Issue68HttpBoundary boundary,
            Issue68FakeOpenAiEndpoint endpoint, Issue68SdkUsage sdk,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> setup) {
        return Issue68OpenAiKafkaHarness.executeExpanded(KAFKA.getBootstrapServers(),
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/issue68_expanded?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "issue68-fixture-only", unit, boundary, endpoint.uri().toString(),
                context -> context.getBean(ObservationRegistry.class).observationConfig().observationHandler(sdk), setup);
    }

    private static void assertComplete(Issue68OpenAiKafkaHarness.Result result, int count) {
        assertThat(result.finalRows()).isEqualTo(count);
        assertThat(result.samples()).allMatch(Issue68OpenAiKafkaHarness.Sample::completed);
        assertThat(result.finalLag()).isZero();
        assertThat(result.dltRecords()).isZero();
        assertThat(result.transactionViolations()).isZero();
        assertThat(result.unsafeCommits()).isZero();
        assertThat(result.saveFailures()).isZero();
        assertThat(result.commitObservations()).isPositive();
    }

    private Path fixture(Issue68ExpandedPlan.Stage stage) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Issue68O0PreparationTest.priorFixture(root.resolve("budget.jsonl"));
        for (var previous : Issue68ExpandedPlan.Stage.values()) {
            if (previous.ordinal() >= stage.ordinal()) break;
            try (var budget = Issue68Budget.startExpandedPreparation(root.resolve("budget.jsonl"), previous,
                    Issue68ExpandedPlan.manifestSha256())) {
                var plan = new Issue68ExpandedPlan(previous);
                for (var unit : Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == previous).toList()) {
                    completeContractOnly(plan, unit);
                }
                budget.completeExpandedStage(plan);
            }
        }
        return root;
    }

    private static void prepareReferenceOnly(Issue68ExpandedPlan plan, Issue68ExpandedPlan.Unit unit) {
        for (var previous : Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == unit.stage()).toList()) {
            if (previous.equals(unit)) break;
            completeContractOnly(plan, previous);
        }
        plan.start(unit);
    }

    private static void completeContractOnly(Issue68ExpandedPlan plan, Issue68ExpandedPlan.Unit unit) {
        plan.start(unit);
        var cases = Issue68ExpandedPlan.cases(unit.stage());
        var targets = cases.stream().filter(c -> new ModerationRulePolicy().clearFlagged(c.content()).isEmpty()).toList();
        int size = unit.mode() == Issue68ExpandedPlan.Mode.NATIVE ? 1 : 10;
        for (int i = 0; i < targets.size(); i += size) {
            var ids = targets.subList(i, Math.min(i + size, targets.size())).stream().map(Issue68EvaluationContract.Case::id).toList();
            plan.begin(ids, ids.size() > 1);
        }
        cases.forEach(c -> plan.committed(c, c.expected(), true));
        plan.completeUnit(true);
    }

    private static Issue68ExpandedPlan.Unit unit(Issue68ExpandedPlan.Stage stage, Issue68ExpandedPlan.Traffic traffic,
            Issue68ExpandedPlan.Mode mode) {
        return Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == stage && u.traffic() == traffic
                && u.repeat() == 1 && u.mode() == mode).findFirst().orElseThrow();
    }

    private static Path lastPartial(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            return files.filter(p -> p.getFileName().toString().contains("-partial-")).sorted().reduce((a, b) -> b).orElse(null);
        }
    }

    private static Object lastCheckpoint(Path root, String name) throws Exception {
        try (var files = Files.walk(root)) {
            var file = files.filter(p -> p.getFileName().toString().contains("-" + name)).sorted().reduce((a, b) -> b);
            return file.isPresent() ? new JsonMapper().readTree(Files.readString(file.get())) : Map.of();
        }
    }

    @AfterAll
    static void write() throws Exception {
        Files.createDirectories(Path.of("build/issue68"));
        Path raw = Path.of("build/issue68/expanded-preparation-" + Instant.now().toEpochMilli() + ".json");
        Files.writeString(raw, new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "scope", "FREE_FAKE_PREPARATION_ONLY_NOT_PAID_APPROVAL_OR_LIVE_QUALITY", "at", Instant.now().toString(),
                "manifest", Issue68ExpandedPlan.manifest(), "manifestSha256", Issue68ExpandedPlan.manifestSha256(),
                "forecastNotUsage", Issue68ExpandedPlan.forecast(), "runs", List.copyOf(RUNS))), java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("ISSUE68_EXPANDED_RAW=" + raw);
    }
}
