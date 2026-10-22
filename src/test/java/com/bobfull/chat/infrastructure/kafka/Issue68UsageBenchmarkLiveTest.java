package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkPlan;
import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

// 단계별 새 승인 ID+전체 source Manifest SHA 없이는 SKIP, Key/장부 접근 없음.
@EnabledIfEnvironmentVariable(named = Issue68UsageBenchmarkSession.APPROVAL_ENV,
        matches = "issue68-usage-benchmark-burst-v1")
@Testcontainers
class Issue68UsageBenchmarkLiveTest {
    @Container static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_usage_live")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void 별도Human승인후_Burst120만_측정하고_다음360은_자동실행하지않는다() throws Exception {
        execute(Issue68UsageBenchmarkPlan.Stage.BURST, KAFKA, MYSQL);
    }

    static void execute(Issue68UsageBenchmarkPlan.Stage stage, ConfluentKafkaContainer kafka, GenericContainer<?> mysql) throws Exception {
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var levels = new java.util.HashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level>();
        logging.getLoggerList().forEach(logger -> { levels.put(logger, logger.getLevel()); logger.setLevel(ch.qos.logback.classic.Level.OFF); });
        var report = new LinkedHashMap<String, Object>();
        var runs = new ArrayList<java.util.Map<String, Object>>();
        try (var session = stage == Issue68UsageBenchmarkPlan.Stage.BURST
                ? Issue68UsageBenchmarkSession.openBurst() : Issue68UsageBenchmarkSession.openRemainder()) {
            try {
                for (var unit : Issue68UsageBenchmarkPlan.units(stage)) {
                    session.plan().start(unit);
                    try (var boundary = session.boundary()) {
                        runs.add(Issue68UsageBenchmarkExecution.execute(kafka.getBootstrapServers(),
                                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                                        + "/issue68_usage_live?useSSL=false&allowPublicKeyRetrieval=true",
                                session, unit, boundary, "https://api.openai.com/v1", owner -> { }));
                    }
                }
                session.completeStage();
                report.put("status", stage.name() + "_USAGE_MEASURED_NOT_QUALITY_PASS_OR_ADOPTION");
            } catch (Throwable failure) {
                session.plan().stop(); report.put("status", "TECHNICAL_HOLD_NO_AUTOMATIC_RESUME");
                report.put("failureClass", failure.getClass().getSimpleName());
                throw failure;
            } finally {
                report.put("runs", runs); report.put("quality", session.plan().quality());
                report.put("benchmarkBurdenNanoUsd", session.budget().benchmarkChargedNanos());
                report.put("globalBurdenNanoUsd", session.budget().chargedNanos());
                report.put("grossReservedNanoUsd", session.budget().grossReservedNanos());
                report.put("historicalGateV1V2", "HOLD_PRESERVED"); report.put("automaticNextStage", false);
                session.checkpoint("final", report);
            }
        } finally { levels.forEach(ch.qos.logback.classic.Logger::setLevel); }
    }
}
