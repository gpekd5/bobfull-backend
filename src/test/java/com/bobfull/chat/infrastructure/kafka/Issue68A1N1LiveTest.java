package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.infrastructure.ai.Issue68A1N1Plan;
import com.bobfull.chat.infrastructure.ai.Issue68A1N1Session;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

// 아직 유료 미승인. 명시적인 새 승인+현재 Manifest 해시 없이는 클래스 전체 SKIP.
@EnabledIfEnvironmentVariable(named = Issue68A1N1Plan.APPROVAL_ENV, matches = Issue68A1N1Plan.APPROVAL)
@Testcontainers
class Issue68A1N1LiveTest {
    @Container static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_a1_n1_live")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void 별도승인된_N1_8건만_실행하고_다음단계로_진행하지_않는다() throws Exception {
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var levels = new java.util.HashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level>();
        logging.getLoggerList().forEach(logger -> { levels.put(logger, logger.getLevel()); logger.setLevel(ch.qos.logback.classic.Level.OFF); });
        try (var session = Issue68A1N1Session.open(); var boundary = session.boundary()) {
            Issue68A1N1Execution.execute(KAFKA.getBootstrapServers(), "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                    + "/issue68_a1_n1_live?useSSL=false&allowPublicKeyRetrieval=true", session, boundary,
                    "https://api.openai.com/v1", context -> { });
        } finally {
            levels.forEach(ch.qos.logback.classic.Logger::setLevel);
        }
    }
}
