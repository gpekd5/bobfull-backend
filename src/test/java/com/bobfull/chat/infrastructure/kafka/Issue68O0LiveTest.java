package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue68GateV2;
import com.bobfull.chat.infrastructure.ai.Issue68O0Plan;
import com.bobfull.chat.infrastructure.ai.Issue68O0Session;
import com.bobfull.chat.infrastructure.ai.Issue68SdkUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

// 아직 실행 승인되지 않았다. 승인ID + 현재manifest SHA + 일회성 고정 장부가 모두 필요하다.
@EnabledIfEnvironmentVariable(named = Issue68O0Session.APPROVAL_ENV, matches = Issue68O0Session.APPROVAL)
@Testcontainers
class Issue68O0LiveTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_o0_live")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void 별도_Human_승인후_O0_첫_Native80_Poll80만_실행하며_HOLD후_자동재개하지_않는다() throws Exception {
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var levels = new java.util.HashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level>();
        logging.getLoggerList().forEach(logger -> {
            levels.put(logger, logger.getLevel());
            logger.setLevel(ch.qos.logback.classic.Level.OFF);
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "O0_FIRST_PAIRED160_HISTORICAL_GT_NOT_BLIND_NOT_PRODUCTION");
        report.put("status", "NOT_STARTED");
        try (var session = Issue68O0Session.open(); var boundary = session.boundary()) {
            var sdk = new Issue68SdkUsage(boundary);
            List<Map<String, Object>> runs = new ArrayList<>();
            Map<String, com.bobfull.chat.application.result.ModerationResult> nativeLabels = new java.util.HashMap<>();
            try {
                var cases = Issue68O0Plan.cases();
                for (boolean batch : List.of(false, true)) {
                    var result = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(),
                            "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68_o0_live?serverTimezone=UTC",
                            "root", "issue68-fixture-only", batch, cases, false, boundary, "https://api.openai.com/v1",
                            context -> context.getBean(io.micrometer.observation.ObservationRegistry.class)
                                    .observationConfig().observationHandler(sdk));
                    var byId = result.samples().stream().collect(Collectors.toMap(
                            Issue68OpenAiKafkaHarness.Sample::caseId, s -> s));
                    var evaluations = cases.stream().map(c -> Issue68GateV2.evaluate(c, byId.get(c.id()).actual(),
                            byId.get(c.id()).completed() && byId.get(c.id()).committedNs() > 0)).toList();
                    var blockers = cases.stream().flatMap(c -> Issue68GateV2.executionBlockers(c, byId.get(c.id()).actual(),
                            byId.get(c.id()).completed() && byId.get(c.id()).committedNs() > 0,
                            !batch, nativeLabels.get(c.id())).stream()).toList();
                    boolean technicalOk = result.finalRows() == 80 && result.finalLag() == 0 && result.dltRecords() == 0
                            && result.saveFailures() == 0 && result.transactionViolations() == 0 && result.unsafeCommits() == 0
                            && result.samples().stream().allMatch(s -> s.completed() && s.committedNs() > 0
                                    && s.committedNs() <= s.returnedNs())
                            && result.gateReasons().stream().noneMatch(reason -> reason.equals("USAGE_UNKNOWN")
                                    || reason.equals("DB_OFFSET_BOUNDARY") || reason.equals("RESPONSE_MODEL_CHANGED")
                                    || reason.equals("TECHNICAL_FAILURE_OBSERVED") || reason.startsWith("LAG_OBSERVATION_FAILED"))
                            && boundary.calls().stream().allMatch(c -> sdk.matches(c.number));
                    runs.add(Map.of("mode", result.mode(), "result", result, "v1v2", evaluations,
                            "executionBlockers", blockers, "technicalOk", technicalOk));
                    session.checkpoint("stage-results", runs);
                    if (!technicalOk || !blockers.isEmpty() || boundary.o0Held()) {
                        report.put("status", batch ? "O0_POLL_GATE_HOLD" : "O0_NATIVE_GATE_HOLD");
                        break;
                    }
                    if (!batch) {
                        result.samples().forEach(s -> nativeLabels.put(s.caseId(), s.actual()));
                        session.plan().beginPoll(evaluations, technicalOk);
                    } else {
                        report.put("paired", cases.stream().map(c -> Map.of("caseId", c.id(), "comparison",
                                Issue68GateV2.paired(c, nativeLabels.get(c.id()), byId.get(c.id()).actual()))).toList());
                        report.put("status", boundary.calls().stream().anyMatch(c -> c.batch)
                                ? "O0_FIRST_COMPARISON_COMPLETED_NOT_ADOPTION" : "O0_NO_BATCH_REQUEST_HOLD");
                    }
                }
            } catch (Throwable failure) {
                report.put("status", "O0_EXECUTION_HOLD");
                report.put("failureClass", failure.getClass().getSimpleName());
                // Provider 원문과 예외 message/cause는 출력하지 않는다.
            } finally {
                boundary.stop();
                report.put("runs", runs);
                report.put("logical", boundary.calls());
                report.put("http", boundary.attempts());
                report.put("sdkUsage", sdk.entries());
                report.put("executionHolds", session.plan().holds());
                report.put("newBurdenNanoUsd", session.budget().stageChargedNanos());
                report.put("totalBurdenNanoUsd", session.budget().chargedNanos());
                report.put("grossReservedNanoUsd", session.budget().grossReservedNanos());
                report.put("manifestSha256", Issue68O0Session.manifestSha256());
                report.put("automaticNextStage", false);
                session.checkpoint("final", report);
                Files.writeString(Path.of("docs/110-records/evidence/v3/68-openai-poll-batch-verification/o0-first-live-results.json"),
                        new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardOpenOption.CREATE_NEW);
            }
        } finally {
            levels.forEach(ch.qos.logback.classic.Logger::setLevel);
        }
        assertThat(report.get("status")).isEqualTo("O0_FIRST_COMPARISON_COMPLETED_NOT_ADOPTION");
    }
}
