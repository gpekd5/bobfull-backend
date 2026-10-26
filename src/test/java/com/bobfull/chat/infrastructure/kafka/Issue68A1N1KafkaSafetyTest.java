package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue68A1N1Plan;
import com.bobfull.chat.infrastructure.ai.Issue68A1N1SafetyTest;
import com.bobfull.chat.infrastructure.ai.Issue68A1N1Session;
import com.bobfull.chat.infrastructure.ai.Issue68FakeOpenAiEndpoint;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class Issue68A1N1KafkaSafetyTest {
    @Container static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_a1_n1")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    @TempDir Path temporary;

    @Test
    void 실제_Native_Consumer_8건의_DBCommit_Offset_Usage와_Metric을_보존한다() throws Exception {
        verify("OK");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRITICAL", "FP", "FN", "CATEGORY", "VALIDATION", "PARSE", "UNKNOWN", "DB", "COMMIT"})
    void N1_HOLD에서도_부분DB_HTTP_SDK_Metric과_안전Offset을_보존하고_후속전송을_막는다(String fault) throws Exception {
        verify(fault);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verify(String fault) throws Exception {
        // given
        Path root = Issue68A1N1SafetyTest.priorFixture(temporary);
        try (var session = Issue68A1N1Session.rehearsal(root); var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = session.rehearsalBoundary(endpoint.uri())) {
            endpoint.gzip(true);
            if (fault.equals("UNKNOWN")) endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.NO_USAGE);
            if (fault.equals("VALIDATION")) endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.VALIDATION);
            if (fault.equals("PARSE")) endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.PARSE);
            if (fault.equals("FN")) endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.SEMANTIC_SAFE);
            if (fault.equals("CRITICAL")) endpoint.overrideLabel(Issue68A1N1Plan.IDS.get(0), new ModerationResult(
                    ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM));
            if (fault.equals("CATEGORY")) endpoint.overrideLabel(Issue68A1N1Plan.IDS.get(0), new ModerationResult(
                    ModerationResultType.FLAGGED, Set.of(ModerationCategory.SPAM), RiskLevel.HIGH));
            if (fault.equals("FP")) endpoint.overrideLabel(Issue68A1N1Plan.IDS.get(4), new ModerationResult(
                    ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM));
            Runnable execute = () -> Issue68A1N1Execution.execute(KAFKA.getBootstrapServers(), jdbc(), session, boundary,
                    endpoint.uri().toString(), context -> {
                        if (fault.equals("DB")) ((Advised) context.getBean(ChatModerationRepository.class)).addAdvice(1,
                                (org.aopalliance.intercept.MethodInterceptor) call -> {
                                    if (call.getMethod().getName().equals("saveAndFlush")) throw new IllegalStateException("fixture-db-write");
                                    return call.proceed();
                                });
                        if (fault.equals("COMMIT")) {
                            var factory = (org.springframework.kafka.core.DefaultKafkaConsumerFactory) context
                                    .getBean(org.springframework.kafka.core.ConsumerFactory.class);
                            factory.addPostProcessor(consumer -> {
                                var proxy = new org.springframework.aop.framework.ProxyFactory(consumer);
                                proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> {
                                    if (call.getMethod().getName().equals("commitSync") || call.getMethod().getName().equals("commitAsync")) {
                                        throw new org.apache.kafka.clients.consumer.CommitFailedException("fixture-commit");
                                    }
                                    return call.proceed();
                                });
                                return (org.apache.kafka.clients.consumer.Consumer) proxy.getProxy();
                            });
                        }
                    });
            // when
            if (fault.equals("OK")) execute.run();
            else assertThatThrownBy(execute::run).isInstanceOf(RuntimeException.class);
            // then
            assertThat(endpoint.received()).isEqualTo(fault.equals("OK") ? 8 : fault.equals("FP") ? 5 : 1);
            assertThat(boundary.calls()).allSatisfy(c -> assertThat(c.batch).isFalse());
            assertThatThrownBy(() -> boundary.begin(List.of(Issue68A1N1Plan.IDS.get(0)), false)).isInstanceOf(IllegalStateException.class);
            var json = new JsonMapper();
            try (var files = Files.list(root.resolve(Issue68A1N1Plan.APPROVAL))) {
                Path partialFile = files.filter(p -> p.getFileName().toString().endsWith("-partial-native.json")).findFirst().orElseThrow();
                var partial = json.readTree(Files.readString(partialFile)).path("data");
                assertThat(partial.path("httpAttempts")).hasSize(endpoint.received());
                assertThat(partial.path("unsafeCommits").asInt()).isZero();
                assertThat(partial.path("offsetSnapshot").asText()).isNotEqualTo("UNAVAILABLE");
                assertThat(partial.path("meters")).isNotEmpty();
                var first = partial.path("cases").get(0);
                assertThat(first.path("receivedNs").asLong()).isPositive();
                if (fault.equals("OK") || fault.equals("CRITICAL") || fault.equals("FP") || fault.equals("FN") || fault.equals("CATEGORY") || fault.equals("COMMIT")) {
                    assertThat(first.path("dbCommittedNs").asLong()).isGreaterThan(first.path("receivedNs").asLong());
                    assertThat(first.path("promptTokens").asLong()).isEqualTo(900);
                    assertThat(partial.path("sdkUsage")).hasSize(endpoint.received());
                }
                if (fault.equals("DB")) assertThat(first.path("dbCommittedNs").asLong()).isZero();
                if (fault.equals("OK")) {
                    assertThat(partial.path("lag").asLong()).isZero();
                    assertThat(partial.path("cases")).hasSize(8);
                    assertThat(partial.path("dltCount").asInt()).isZero();
                }
                if (fault.equals("COMMIT")) assertThat(partial.path("lag").asLong()).isPositive();
                if (fault.equals("UNKNOWN")) assertThat(session.budget().stageChargedNanos()).isEqualTo(19_276_800L);
                if (fault.equals("CRITICAL")) assertThat(session.plan().holds().get(Issue68A1N1Plan.IDS.get(0))).contains("CRITICAL_RISK_DOWNGRADE");
                if (fault.equals("FP")) assertThat(session.plan().holds().get(Issue68A1N1Plan.IDS.get(4))).contains("FP_HUMAN_REVIEW");
                Files.createDirectories(Path.of("build/issue68"));
                Files.writeString(Path.of("build/issue68/a1-n1-free-" + fault + "-" + System.currentTimeMillis() + ".json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of("scope", "FREE_FAKE_KAFKA_MYSQL_NOT_AI_QUALITY",
                                "fault", fault, "partial", partial, "evaluations", session.plan().evaluations())), java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        }
    }

    private static String jdbc() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68_a1_n1?useSSL=false&allowPublicKeyRetrieval=true";
    }
}
