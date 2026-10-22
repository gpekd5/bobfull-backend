package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue68EvaluationContract;
import com.bobfull.chat.infrastructure.ai.Issue68LiveSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

// 별도 승인된 Native1/Batch1만 실행한다. 일반 test/build에서는 환경 Gate로 건너뛴다.
@EnabledIfEnvironmentVariable(named = "ISSUE68_LIVE_APPROVAL", matches = Issue68LiveSession.RESUME_APPROVAL)
@Testcontainers
class Issue68LiveSmokeTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void 승인된_Native1과_Kafka_Batch1만_실행하고_다음_Matrix는_진행하지_않는다() throws Exception {
        // given: 로그에 Provider 오류/인증정보를 남기지 않는다. 원본 오류는 Evidence로 내보내지 않는다.
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var levels = new java.util.HashMap<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level>();
        logging.getLoggerList().forEach(logger -> {
            levels.put(logger, logger.getLevel());
            logger.setLevel(ch.qos.logback.classic.Level.OFF);
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "ISSUE68_LIVE_SMOKE_ONLY_NOT_MATRIX_OR_PRODUCTION");
        report.put("groundTruthSha256", Issue68EvaluationContract.groundTruthSha256());
        report.put("approval", Issue68LiveSession.RESUME_APPROVAL);
        report.put("previousApproval", Issue68LiveSession.APPROVAL);
        report.put("maxNewHttpAttempts", Issue68LiveSession.RESUME_HTTP_LIMIT);
        report.put("recordedAt", java.time.Instant.now().toString());
        report.put("status", "NOT_STARTED");
        try (var session = Issue68LiveSession.resume(); var boundary = session.boundary()) {
            var sdk = new com.bobfull.chat.infrastructure.ai.Issue68SdkUsage(boundary);
            long initialCharged = session.budget().chargedNanos();
            long initialGross = session.budget().grossReservedNanos();
            try {
                // 모델 접근 확인은 Chat Completion이 아닌 읽기 요청 1회다. 오류 본문은 저장하지 않는다.
                String key = System.getenv("OPENAI_API_KEY");
                if (key == null || key.isBlank() || key.contains("\n") || key.contains("\r")) {
                    throw new IllegalStateException("ISSUE68_KEY_UNAVAILABLE");
                }
                var modelResponse = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10)).build().send(HttpRequest.newBuilder()
                                .uri(URI.create("https://api.openai.com/v1/models/gpt-4o-mini"))
                                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + key).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                report.put("modelAccessHttpStatus", modelResponse.statusCode());
                if (modelResponse.statusCode() != 200 || !new JsonMapper().readTree(modelResponse.body())
                        .path("id").asText().equals("gpt-4o-mini")) {
                    throw new IllegalStateException("ISSUE68_MODEL_UNAVAILABLE");
                }
                var cases = Issue68LiveSession.smokeCases();
                report.put("cases", cases.stream().map(c -> Map.of("caseId", c.id(), "expected", c.expected(),
                        "contentSha256", Issue68EvaluationContract.sha256(c.content()))).toList());
                String jdbc = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68?serverTimezone=UTC";
                // when: Native의 한 입력은 Batch에도 포함된다. 추가 한 입력의 Native 호출은 승인 범위에 없다.
                var nativeResult = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(), jdbc,
                        "root", "issue68-fixture-only", false, cases.subList(0, 1), false,
                        boundary, "https://api.openai.com/v1", context -> context
                                .getBean(io.micrometer.observation.ObservationRegistry.class)
                                .observationConfig().observationHandler(sdk), 1);
                report.put("native", nativeResult);
                report.put("nativeSdkHttpUsageMatch", sdk.matches(1));
                if (!nativeResult.gateReasons().isEmpty() || !sdk.matches(1)) {
                    report.put("status", "NATIVE_GATE_HOLD");
                } else {
                    var batchResult = Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(), jdbc,
                            "root", "issue68-fixture-only", true, cases, false,
                            boundary, "https://api.openai.com/v1", context -> context
                                    .getBean(io.micrometer.observation.ObservationRegistry.class)
                                    .observationConfig().observationHandler(sdk), 1);
                    report.put("batch", batchResult);
                    report.put("batchSdkHttpUsageMatch", sdk.matches(2));
                    boolean actualBatch = boundary.calls().size() == 2 && boundary.calls().get(1).batch
                            && boundary.calls().get(1).caseIds.size() >= 2;
                    report.put("actualKafkaBatchConfirmed", actualBatch);
                    report.put("status", batchResult.gateReasons().isEmpty() && sdk.matches(2) && actualBatch
                            ? "SMOKE_COMPLETED" : "BATCH_GATE_HOLD");
                }
            } catch (Throwable failure) {
                boundary.stop();
                report.put("status", "EXECUTION_STOPPED");
                report.put("failureClass", failure.getClass().getSimpleName());
                // message/cause는 Provider 응답을 포함할 수 있으므로 의도적으로 보존하지 않는다.
            } finally {
                boundary.stop();
                report.put("requests", boundary.calls());
                report.put("http", boundary.attempts());
                report.put("sdkUsage", sdk.entries());
                report.put("priorUnknownReservedNanoUsd", initialCharged);
                report.put("priorGrossReservedNanoUsd", initialGross);
                report.put("newChargedOrUnknownReservedNanoUsd", session.budget().chargedNanos() - initialCharged);
                report.put("newGrossReservedNanoUsd", session.budget().grossReservedNanos() - initialGross);
                report.put("chargedOrUnknownReservedNanoUsd", session.budget().chargedNanos());
                report.put("grossReservedNanoUsd", session.budget().grossReservedNanos());
                report.put("matrixExecuted", false);
                report.put("executionHead", "8ffb5aabc1df96e0a20d08b0f29eab89ac069807");
                report.put("executionState", "uncommitted-source-snapshot");
                Map<String, String> hashes = new java.util.TreeMap<>();
                for (String directory : List.of("src/test/java/com/bobfull/chat/infrastructure/ai",
                        "src/test/java/com/bobfull/chat/infrastructure/kafka")) {
                    try (var paths = Files.list(Path.of(directory))) {
                        for (var file : paths.filter(p -> p.getFileName().toString().startsWith("Issue68")
                                && p.toString().endsWith(".java")).toList()) {
                            hashes.put(file.toString(), Issue68EvaluationContract.sha256(Files.readString(file)));
                        }
                    }
                }
                report.put("sourceHashes", hashes);
                session.checkpoint("final", report);
                Files.writeString(Path.of("docs/110-records/evidence/v3/68-openai-poll-batch-verification/live-resume-usage-v1-results.json"),
                        new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        } finally {
            levels.forEach(ch.qos.logback.classic.Logger::setLevel);
        }
        // then: 품질 Hold도 합격으로 처리하지 않는다. 결과를 보존한 뒤 Human에게 보고한다.
        assertThat(report.get("status")).isEqualTo("SMOKE_COMPLETED");
    }
}
