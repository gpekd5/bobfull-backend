package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue68Budget;
import com.bobfull.chat.infrastructure.ai.Issue68EvaluationContract;
import com.bobfull.chat.infrastructure.ai.Issue68FakeOpenAiEndpoint;
import com.bobfull.chat.infrastructure.ai.Issue68GateV2;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.infrastructure.ai.Issue68O0Plan;
import com.bobfull.chat.infrastructure.ai.Issue68O0PreparationTest;
import com.bobfull.chat.infrastructure.ai.Issue68SdkUsage;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class Issue68O0KafkaPreparationTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_o0")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    @TempDir
    Path temporary;

    @Test
    void Native80_Poll80은_동일입력과_실제Consumer_DB_Offset_예산계약으로_무과금_연결된다() throws Exception {
        rehearse(false);
    }

    @Test
    void Native_일반FP가_실제_DB에_남아도_Poll80을_실행하고_공통FP를_보존한다() throws Exception {
        rehearse(true);
    }

    private void rehearse(boolean commonFp) throws Exception {
        // given
        var plan = new Issue68O0Plan();
        var cases = Issue68O0Plan.cases();
        List<Map<String, Object>> runs = new ArrayList<>();
        List<Issue68GateV2.Evaluation> nativeEvaluations = List.of();
        try (var budget = Issue68Budget.startO0Preparation(
                Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl")));
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            endpoint.gzip(true);
            var fp = new com.bobfull.chat.application.result.ModerationResult(
                    com.bobfull.chat.domain.entity.ModerationResultType.FLAGGED,
                    java.util.Set.of(com.bobfull.chat.domain.entity.ModerationCategory.SPAM),
                    com.bobfull.chat.domain.entity.RiskLevel.MEDIUM);
            if (commonFp) {
                endpoint.overrideLabel("HOLDOUT-SAFE-10", fp);
            }
            for (boolean batch : List.of(false, true)) {
                try (var boundary = Issue68HttpBoundary.o0Preparation(endpoint.uri(), budget, plan)) {
                    var sdk = new Issue68SdkUsage(boundary);
                    // when: 실제 Consumer/Adapter/JPA/afterCommit/Offset, 서로 다른3 partition, Fake HTTP만 사용한다.
                    var result = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(),
                            "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                                    + "/issue68_o0?useSSL=false&allowPublicKeyRetrieval=true",
                            "root", "issue68-fixture-only", batch, cases, false, boundary, endpoint.uri().toString(),
                            context -> context.getBean(ObservationRegistry.class).observationConfig().observationHandler(sdk));
                    var byId = result.samples().stream().collect(Collectors.toMap(
                            Issue68OpenAiKafkaHarness.Sample::caseId, s -> s));
                    var evaluations = cases.stream().map(c -> Issue68GateV2.evaluate(c, byId.get(c.id()).actual(),
                            byId.get(c.id()).completed() && byId.get(c.id()).committedNs() > 0)).toList();
                    // then
                    assertThat(result.samples()).hasSize(80).allMatch(s -> s.completed()
                            && s.committedNs() > 0 && s.committedNs() <= s.returnedNs());
                    assertThat(result.finalRows()).isEqualTo(80);
                    assertThat(result.finalLag()).isZero();
                    assertThat(result.unsafeCommits()).isZero();
                    assertThat(result.transactionViolations()).isZero();
                    assertThat(result.dltRecords()).isZero();
                    assertThat(result.saveFailures()).isZero();
                    assertThat(result.gateReasons()).isEmpty();
                    assertThat(boundary.attempts()).hasSize(boundary.calls().size())
                            .allMatch(a -> a.usage() != null && a.settlement().equals("SETTLED"));
                    assertThat(boundary.calls()).allMatch(c -> sdk.matches(c.number));
                    assertThat(boundary.calls().stream().mapToInt(c -> c.caseIds.size()).sum()).isEqualTo(79);
                    assertThat(cases).allMatch(c -> Issue68GateV2.executionBlockers(c, byId.get(c.id()).actual(), true,
                            !batch, commonFp && c.id().equals("HOLDOUT-SAFE-10") ? fp : c.expected()).isEmpty());
                    assertThat(result.quality().fp()).isEqualTo(commonFp ? 1 : 0);
                    if (commonFp) {
                        assertThat(evaluations.stream().filter(e -> e.id().equals("HOLDOUT-SAFE-10")).findFirst().orElseThrow()
                                .v2Blockers()).contains("FP_HUMAN_REVIEW");
                    }
                    if (!batch) {
                        assertThat(boundary.calls()).hasSize(79).allMatch(c -> !c.batch);
                        nativeEvaluations = evaluations;
                        plan.beginPoll(evaluations, true);
                    } else {
                        assertThat(boundary.calls()).anyMatch(c -> c.batch && c.caseIds.size() >= 2);
                        assertThat(boundary.calls().size()).isBetween(8, 79);
                        var batched = boundary.calls().stream().filter(c -> c.batch).flatMap(c -> c.caseIds.stream()).toList();
                        assertThat(result.samples().stream().filter(s -> batched.contains(s.caseId())))
                                .allMatch(s -> s.promptTokens() == null && s.totalTokens() == null);
                    }
                    var sizes = boundary.calls().stream().collect(Collectors.groupingBy(c -> c.caseIds.size(), Collectors.counting()));
                    runs.add(Map.of("mode", result.mode(), "result", result, "logicalRequests", boundary.calls(),
                            "httpAttempts", boundary.attempts(), "sdkUsage", sdk.entries(), "actualRequestSizeHistogram", sizes,
                            "gateV2", evaluations, "v1Quality", result.quality()));
                }
            }
            assertThat(nativeEvaluations).hasSize(80);
            assertThat(plan.stopped()).isFalse();
            assertThat(budget.stageChargedNanos()).isLessThanOrEqualTo(Issue68Budget.O0_STAGE_LIMIT);
            assertThat(budget.chargedNanos()).isLessThanOrEqualTo(Issue68Budget.PLANNED_LIMIT);
            assertThat(budget.stageGrossReservedNanos()).isGreaterThan(Issue68Budget.O0_STAGE_LIMIT);
            Files.createDirectories(Path.of("build/issue68"));
            Files.writeString(Path.of("build/issue68/" + (commonFp ? "o0-native-fp-rehearsal.json" : "o0-fake-rehearsal.json")),
                    new JsonMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("scope", "FAKE_CONTRACT_REHEARSAL_NOT_MODEL_QUALITY_OR_REAL_USAGE",
                            "contract", Issue68O0Plan.frozenContract(), "runs", runs,
                            "stageHttpAttempts", budget.stageAttempts(), "chargedNanoUsd", budget.chargedNanos(),
                            "stageChargedNanoUsd", budget.stageChargedNanos(), "grossReservedNanoUsd", budget.grossReservedNanos())));
        }
    }

    @Test
    void O0_DB저장_실패후에는_다음_HTTP를_차단하고_부분_Offset과_DB상태를_보존한다() throws Exception {
        // given
        var cases = Issue68O0Plan.cases().stream().filter(c -> c.id().equals("HOLDOUT-SAFE-01")
                || c.id().equals("HOLDOUT-SAFE-02")).toList();
        var snapshots = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        try (var budget = Issue68Budget.startO0Preparation(
                Issue68O0PreparationTest.priorFixture(temporary.resolve("db-failure.jsonl")));
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = Issue68HttpBoundary.o0Preparation(endpoint.uri(), budget, new Issue68O0Plan())) {
            boundary.checkpoints(snapshots::put);
            // when & then
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Issue68OpenAiKafkaHarness.execute(
                    KAFKA.getBootstrapServers(), "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                            + "/issue68_o0?useSSL=false&allowPublicKeyRetrieval=true", "root", "issue68-fixture-only",
                    false, cases, false, boundary, endpoint.uri().toString(), context -> {
                        var advised = (org.springframework.aop.framework.Advised) context.getBean(
                                com.bobfull.chat.infrastructure.repository.ChatModerationRepository.class);
                        advised.addAdvice(1, (org.aopalliance.intercept.MethodInterceptor) call -> {
                            if (call.getMethod().getName().equals("saveAndFlush")) {
                                throw new org.springframework.dao.TransientDataAccessResourceException("issue68-fixture-db-failure");
                            }
                            return call.proceed();
                        });
                    })).isInstanceOf(RuntimeException.class);
            assertThat(endpoint.received()).isEqualTo(1);
            assertThat(boundary.o0Held()).isTrue();
            assertThat(snapshots).containsKey("partial-native");
            var partial = (Map<?, ?>) snapshots.get("partial-native");
            assertThat(partial.get("unsafeCommits")).isEqualTo(0);
        }
    }

    @Test
    void O0_Commit_오류_통지를_기존_ErrorHandler에_위임하면서_추가_HTTP는_차단한다() throws Exception {
        // given: 실제 broker commit 장애 재현이 아니라 실제 Handler Bean의 오류 통지 경계 검증이다.
        var cases = Issue68O0Plan.cases().stream().filter(c -> c.id().equals("HOLDOUT-SAFE-01")).toList();
        try (var budget = Issue68Budget.startO0Preparation(
                Issue68O0PreparationTest.priorFixture(temporary.resolve("commit-notification.jsonl")));
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = Issue68HttpBoundary.o0Preparation(endpoint.uri(), budget, new Issue68O0Plan())) {
            // when & then
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Issue68OpenAiKafkaHarness.execute(
                    KAFKA.getBootstrapServers(), "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                            + "/issue68_o0?useSSL=false&allowPublicKeyRetrieval=true", "root", "issue68-fixture-only",
                    false, cases, false, boundary, endpoint.uri().toString(), context -> context.getBean(
                            "chatModerationErrorHandler", org.springframework.kafka.listener.CommonErrorHandler.class)
                            .handleOtherException(new org.apache.kafka.clients.consumer.CommitFailedException(
                                    "issue68-fixture-notification"), null, null, false)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(boundary.o0Held()).isTrue();
            assertThat(endpoint.received()).isZero();
            assertThat(budget.stageAttempts()).isZero();
        }
    }
}
