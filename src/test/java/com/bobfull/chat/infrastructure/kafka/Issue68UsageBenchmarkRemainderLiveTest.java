package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkPlan;
import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkSession;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

// Burst를 재실행하지 않는다. 새 REMAINDER 승인과 전체 source Manifest SHA가 있어야만 진입한다.
@EnabledIfEnvironmentVariable(named = Issue68UsageBenchmarkSession.APPROVAL_ENV,
        matches = "issue68-usage-benchmark-remainder-v1")
@Testcontainers
class Issue68UsageBenchmarkRemainderLiveTest {
    @Container static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_usage_live")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void 별도Human승인후_나머지360만_측정하고_Burst와_자동재개는_금지한다() throws Exception {
        Issue68UsageBenchmarkLiveTest.execute(Issue68UsageBenchmarkPlan.Stage.REMAINDER, KAFKA, MYSQL);
    }
}
