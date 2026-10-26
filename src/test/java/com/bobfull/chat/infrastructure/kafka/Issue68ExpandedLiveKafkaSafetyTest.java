package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.infrastructure.ai.Issue68Budget;
import com.bobfull.chat.infrastructure.ai.Issue68EvaluationContract;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedLiveSession;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import com.bobfull.chat.infrastructure.ai.Issue68FakeOpenAiEndpoint;
import com.bobfull.chat.infrastructure.ai.Issue68LiveSession;
import com.bobfull.chat.infrastructure.ai.Issue68O0PreparationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class Issue68ExpandedLiveKafkaSafetyTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_live_rehearsal")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    @TempDir Path temporary;

    private Path fixture() throws Exception {
        Path root = Files.createDirectory(temporary.resolve(java.util.UUID.randomUUID().toString())).toRealPath();
        Issue68O0PreparationTest.priorFixture(root.resolve("budget.jsonl"));
        Files.writeString(root.resolve("approval.claim"), Issue68LiveSession.APPROVAL + "\n"
                + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n");
        return root;
    }

    @Test
    void Live와_같은_Pilot승인_장부_실행기로_여섯조건120건을_loopback에서_검증한다() throws Exception {
        // given
        var runs = new ArrayList<Map<String, Object>>();
        Path root = fixture();
        String prior = Files.readString(root.resolve("budget.jsonl"));
        try (var session = Issue68ExpandedLiveSession.rehearsal(root); var endpoint = new Issue68FakeOpenAiEndpoint()) {
            endpoint.gzip(true);
            endpoint.beforeResponse(() -> java.util.concurrent.locks.LockSupport.parkNanos(60_000_000));
            // when
            for (var unit : Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == session.plan().stage()).toList()) {
                session.plan().start(unit);
                try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                    var run = Issue68ExpandedPilotExecution.execute(KAFKA.getBootstrapServers(), jdbc(), session, unit,
                            boundary, endpoint.uri().toString(), owner -> { });
                    runs.add(run);
                    // then: GT echo는 실제 모델 품질 검증이 아니다.
                    assertThat(run.get("technicalOk")).isEqualTo(true);
                    assertThat(boundary.calls()).isNotEmpty();
                    if (unit.mode() != Issue68ExpandedPlan.Mode.NATIVE) assertThat(boundary.calls()).anyMatch(c -> c.batch);
                }
            }
            session.completePilot();
            assertThat(session.plan().complete()).isTrue();
            assertThat(session.budget().stageChargedNanos()).isPositive();
            assertThat(session.budget().chargedNanos()).isLessThan(Issue68Budget.EXPANDED_LIMIT);
            assertThatThrownBy(() -> session.budget().reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
            Files.createDirectories(Path.of("build/issue68"));
            Files.writeString(Path.of("build/issue68/expanded-live-rehearsal-" + System.currentTimeMillis() + ".json"),
                    new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "scope", "FREE_LOOPBACK_LIVE_ACCOUNTING_NOT_PAID_OR_MODEL_QUALITY", "runs", runs,
                            "burden", session.budget().chargedNanos(), "gross", session.budget().grossReservedNanos(),
                            "manifestSha256", Issue68ExpandedLiveSession.manifestSha256())), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        assertThat(Files.readString(root.resolve("budget.jsonl"))).startsWith(prior);
        assertThatThrownBy(() -> Issue68ExpandedLiveSession.rehearsal(root)).hasMessage("ISSUE68_PILOT_REAPPROVAL_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMIT", "UNKNOWN", "QUALITY", "BUDGET"})
    void Owner중단은_Live장부에서도_후속_HTTP와_다음조건을_차단하고_부분결과를_보존한다(String fault) throws Exception {
        // given: 이전 조건은 측정이 아니라 공개 GT 계약 fixture로만 채운다.
        Path root = fixture();
        try (var session = Issue68ExpandedLiveSession.rehearsal(root); var endpoint = new Issue68FakeOpenAiEndpoint()) {
            var target = Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == session.plan().stage()
                    && u.mode() == Issue68ExpandedPlan.Mode.TIME_100).findFirst().orElseThrow();
            for (var previous : Issue68ExpandedPlan.matrix()) {
                if (previous.equals(target)) break;
                completeReferenceContract(session.plan(), previous);
            }
            session.plan().start(target);
            if (fault.equals("UNKNOWN")) endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.NO_USAGE);
            if (fault.equals("QUALITY")) endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.SEMANTIC_SAFE);
            if (fault.equals("BUDGET")) for (int i = 0; i < 5; i++) session.budget().settle(session.budget().reserve(1280),
                    new Issue68Budget.Usage(128000, 1280, 129280, 0L));
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                // when
                assertThatThrownBy(() -> Issue68ExpandedPilotExecution.execute(KAFKA.getBootstrapServers(), jdbc(),
                        session, target, boundary, endpoint.uri().toString(), owner -> {
                            if (fault.equals("COMMIT")) owner.beforeCommit = offsets -> {
                                throw new org.apache.kafka.clients.consumer.CommitFailedException("fixture");
                            };
                        })).isInstanceOf(RuntimeException.class);
                int received = endpoint.received();
                // then
                assertThat(session.plan().stopped()).isTrue();
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false)).isInstanceOf(IllegalStateException.class);
                assertThat(endpoint.received()).isEqualTo(received);
                assertThat(received).isEqualTo(fault.equals("BUDGET") ? 0 : 1);
                try (var files = Files.walk(root)) {
                    assertThat(files.filter(p -> p.getFileName().toString().contains("-partial-")).count()).isPositive();
                }
                if (fault.equals("UNKNOWN")) assertThat(session.budget().stageChargedNanos())
                        .isEqualTo(boundary.attempts().get(0).reservedNanos());
                assertThatThrownBy(() -> session.plan().completeUnit(true)).hasMessage("ISSUE68_EXPANDED_HOLD");
            }
        }
    }

    private static String jdbc() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/issue68_live_rehearsal?useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static void completeReferenceContract(Issue68ExpandedPlan plan, Issue68ExpandedPlan.Unit unit) {
        plan.start(unit);
        var cases = Issue68ExpandedPlan.cases(unit.stage());
        int size = unit.mode() == Issue68ExpandedPlan.Mode.NATIVE ? 1 : 10;
        for (int i = 0; i < cases.size(); i += size) {
            var ids = cases.subList(i, Math.min(i + size, cases.size())).stream()
                    .map(Issue68EvaluationContract.Case::id).toList();
            plan.begin(ids, ids.size() > 1);
        }
        cases.forEach(c -> plan.committed(c, c.expected(), true));
        plan.completeUnit(true);
    }
}
