package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue68ExpandedLiveSession;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

// 현재 미승인. 이 전용 승인 + 현재 Manifest SHA가 없으면 클래스 전체 SKIP된다.
@EnabledIfEnvironmentVariable(named = Issue68ExpandedLiveSession.APPROVAL_ENV, matches = Issue68ExpandedLiveSession.APPROVAL)
@Testcontainers
class Issue68ExpandedPilotLiveTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_pilot_live")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void 별도승인후에만_여섯조건_Pilot120을_실행하고_HOLD와_종료후_자동재개하지_않는다() throws Exception {
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var levels = new java.util.HashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level>();
        logging.getLoggerList().forEach(logger -> {
            levels.put(logger, logger.getLevel());
            logger.setLevel(ch.qos.logback.classic.Level.OFF);
        });
        var report = new LinkedHashMap<String, Object>();
        report.put("scope", "LIVE_PILOT120_HISTORICAL_GT_NOT_BLIND_NOT_PRODUCTION");
        report.put("status", "NOT_STARTED");
        var runs = new ArrayList<Map<String, Object>>();
        try (var session = Issue68ExpandedLiveSession.open()) {
            try {
                for (var unit : Issue68ExpandedPlan.matrix().stream()
                        .filter(u -> u.stage() == Issue68ExpandedPlan.Stage.PILOT).toList()) {
                    session.plan().start(unit);
                    try (var boundary = session.boundary()) {
                        runs.add(Issue68ExpandedPilotExecution.execute(KAFKA.getBootstrapServers(),
                                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                                        + "/issue68_pilot_live?useSSL=false&allowPublicKeyRetrieval=true",
                                session, unit, boundary, "https://api.openai.com/v1", owner -> { }));
                    }
                }
                session.completePilot();
                report.put("status", "PILOT_COMPLETED_NOT_ADOPTION_NO_AUTOMATIC_MAIN");
            } catch (Throwable failure) {
                session.plan().stop();
                report.put("status", "PILOT_HOLD_HUMAN_REAPPROVAL_REQUIRED");
                report.put("failureClass", failure.getClass().getSimpleName());
            } finally {
                report.put("runs", runs);
                report.put("v1v2", session.plan().evaluations());
                report.put("executionHolds", session.plan().holds());
                report.put("newBurdenNanoUsd", session.budget().stageChargedNanos());
                report.put("totalBurdenNanoUsd", session.budget().chargedNanos());
                report.put("grossReservedNanoUsd", session.budget().grossReservedNanos());
                report.put("manifestSha256", Issue68ExpandedLiveSession.manifestSha256());
                report.put("automaticNextStage", false);
                session.checkpoint("final", report); // 기존 Evidence를 덮어쓰지 않고 고정 새 실행 directory에 기록한다.
            }
        } finally {
            levels.forEach(ch.qos.logback.classic.Logger::setLevel);
        }
        assertThat(report.get("status")).isEqualTo("PILOT_COMPLETED_NOT_ADOPTION_NO_AUTOMATIC_MAIN");
    }
}
