package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue68Budget;
import com.bobfull.chat.infrastructure.ai.Issue68EvaluationContract;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import com.bobfull.chat.infrastructure.ai.Issue68FakeOpenAiEndpoint;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.infrastructure.ai.Issue68MonitoringBridge;
import com.bobfull.chat.infrastructure.ai.Issue68SdkUsage;
import io.micrometer.observation.ObservationRegistry;
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
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class Issue68HoldObservabilityTest {
    private static final JsonMapper JSON = new JsonMapper();
    @Container static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_observation")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 정상과_CriticalHOLD_모두_최초수신_DB_HTTP_SDK와_Metric을_보존한다(boolean hold) throws Exception {
        verify(hold, false, false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ISSUE68_LOCAL_MONITORING", matches = "true")
    void Fake_HOLD_후에도_실제_로컬_Prometheus가_분리된_Metric을_수집한다() throws Exception {
        verify(true, true, false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ISSUE68_DASHBOARD_CAPTURE", matches = "true")
    void 기존_Dashboard_원본PromQL을_Fake전용_Prometheus에서_검증하고_캡처구간을_유지한다() throws Exception {
        verify(true, true, true);
    }

    private void verify(boolean hold, boolean local, boolean dashboardCapture) throws Exception {
        String prometheus = dashboardCapture ? "http://127.0.0.1:19068" : "http://127.0.0.1:9090";
        String prefix = dashboardCapture ? "" : "issue68_test_";
        boolean discovery = local && !dashboardCapture;
        Map<String, Object> checkpoints = new ConcurrentHashMap<>();
        var cases = List.of(caseById("HOLDOUT-SAFE-01"), caseById("HOLDOUT-PROFANITY-14"));
        var plan = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT);
        plan.start(Issue68ExpandedPlan.matrix().get(0));
        Path targets = Path.of("build/issue68/monitoring/targets.json");
        if (discovery) {
            Files.createDirectories(targets.getParent());
            assertThat(Files.exists(targets) ? Files.readString(targets).trim() : "[]").isEqualTo("[]");
        }
        try (var bridge = new Issue68MonitoringBridge(local ? 18068 : 0);
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("fake-only.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = Issue68HttpBoundary.expandedPreparation(endpoint.uri(), budget, plan)) {
            boundary.fakeMonitoring(bridge::registry, bridge::freeze);
            boundary.checkpoints(checkpoints::put);
            if (hold) {
                var expected = cases.get(1).expected();
                endpoint.overrideLabel(cases.get(1).id(), new ModerationResult(expected.result(), expected.categories(), RiskLevel.MEDIUM));
            }
            endpoint.gzip(true);
            if (local) {
                if (discovery) Files.writeString(targets, "[{\"targets\":[\"host.docker.internal:18068\"]}]");
                if (dashboardCapture) {
                    // 기존 TSDB/다른 실행 값이 있는 경우 캡처를 새 실험으로 가장하지 않는다.
                    assertThat(value(prometheus, "sum(gen_ai_client_operation_seconds_count)")).isEqualTo(-1.0);
                }
                endpoint.beforeResponse(() -> {
                    if (endpoint.received() == 2) await().atMost(Duration.ofSeconds(25))
                            .untilAsserted(() -> assertThat(value(prometheus, "sum(" + prefix + "gen_ai_client_operation_seconds_count)")).isEqualTo(1.0));
                });
            }
            Runnable execution = () -> Issue68OpenAiKafkaHarness.execute(KAFKA.getBootstrapServers(),
                    "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                            + "/issue68_observation?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", "issue68-fixture-only", false, cases, false, boundary, endpoint.uri().toString(), context ->
                            context.getBean(ObservationRegistry.class).observationConfig().observationHandler(new Issue68SdkUsage(boundary)), 1);
            if (hold) assertThatThrownBy(execution::run).isInstanceOf(IllegalStateException.class);
            else execution.run();
            var partial = JSON.valueToTree(checkpoints.get("partial-native"));
            assertThat(partial.path("cases")).hasSize(2);
            for (var row : partial.path("cases")) {
                assertThat(row.path("receivedNs").asLong()).isPositive();
                assertThat(row.path("dbCommittedNs").asLong()).isGreaterThan(row.path("receivedNs").asLong());
                assertThat(row.path("promptTokens").asLong()).isEqualTo(900);
                assertThat(row.path("model").asText()).isEqualTo("gpt-4o-mini-2024-07-18");
                assertThat(row.path("receivedToCommitMs").asDouble()).isPositive();
                assertThat(row.path("v1v2")).isNotNull();
            }
            assertThat(partial.path("sdkUsage")).hasSize(2);
            assertThat(partial.path("httpAttempts")).hasSize(2);
            assertThat(boundary.sdkEntries()).extracting(Issue68SdkUsage.Entry::usage)
                    .containsExactlyElementsOf(boundary.attempts().stream().map(Issue68HttpBoundary.Attempt::usage).toList());
            assertThat(partial.path("unsafeCommits").asInt()).isZero();
            assertThat(partial.path("lag").asLong()).isZero();
            assertThat(partial.path("dltCount").asInt()).isZero();
            assertThat(plan.stopped()).isEqualTo(hold);
            assertThat(endpoint.received()).isEqualTo(2);
            for (var meter : partial.path("meters")) {
                for (var tag : meter.path("tags")) {
                    assertThat(tag.path("key").asText()).doesNotContainIgnoringCase("messageId", "eventId",
                            "chatRoomId", "memberId", "content", "exception.message", "offset");
                    assertThat(tag.path("value").asText()).doesNotContain(cases.get(0).content(), cases.get(1).content());
                }
            }
            assertThat(bridge.scrape()).contains("gen_ai_client_operation_seconds_bucket", "gen_ai_client_token_usage_total",
                    "okhttp_requests_seconds_count", "bobfull_ai_moderation_final_status_total");
            if (hold) assertThatThrownBy(() -> boundary.begin(List.of(cases.get(0).id()), false)).isInstanceOf(IllegalStateException.class);
            if (local) {
                Map<String, Object> queries = new LinkedHashMap<>();
                await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(
                        value(prometheus, "sum(" + prefix + "gen_ai_client_operation_seconds_count)")).isEqualTo(2.0));
                for (String query : List.of("sum(issue68_test_gen_ai_client_operation_seconds_count)",
                        "sum by(gen_ai_token_type)(issue68_test_gen_ai_client_token_usage_total)",
                        "sum by(route)(issue68_test_bobfull_ai_moderation_route_total)",
                        "sum by(status)(issue68_test_bobfull_ai_moderation_final_status_total)",
                        "histogram_quantile(0.95,sum by(le)(rate(issue68_test_gen_ai_client_operation_seconds_bucket[5m])))",
                        "sum by(gen_ai_token_type)(rate(issue68_test_gen_ai_client_token_usage_total[5m]))",
                        "sum(issue68_test_bobfull_ai_moderation_kafka_retry_total) or vector(0)",
                        "sum(issue68_test_bobfull_ai_moderation_failure_total) or vector(0)",
                        "sum(issue68_test_okhttp_requests_seconds_count)")) {
                    query = query.replace("issue68_test_", prefix);
                    var data = query(prometheus, query);
                    assertThat(data.path("data").path("result")).isNotEmpty();
                    assertThat(data.toString()).doesNotContain("NaN");
                    queries.put(query, data);
                }
                Map<String, Object> panelQueries = new LinkedHashMap<>();
                if (dashboardCapture) {
                    var source = JSON.readTree(Files.readString(Path.of("ops/local-monitoring/grafana/provisioning/dashboards/bobfull-ai-moderation-observability.json")));
                    for (var panel : source.path("panels")) {
                        int id = panel.path("id").asInt();
                        if (!List.of(3, 4, 5, 12).contains(id)) continue;
                        String expression = panel.path("targets").get(0).path("expr").asText();
                        var result = query(prometheus, expression);
                        assertThat(result.path("data").path("result")).isNotEmpty();
                        assertThat(result.toString()).doesNotContain("NaN");
                        if (id == 3) assertThat(value(prometheus, expression)).isEqualTo(2);
                        if (id == 12) assertThat(value(prometheus, expression)).isCloseTo(.000294,
                                org.assertj.core.data.Offset.offset(.000000001));
                        panelQueries.put(panel.path("title").asText(), Map.of("expr", expression, "result", result));
                    }
                    assertThat(value(prometheus, "count(gen_ai_client_operation_seconds_count{harness_scope!=\"issue68_fake\"}) or vector(0)")).isZero();
                }
                var artifact = Map.of("scope", "FREE_FAKE_HTTP_NOT_MODEL_QUALITY_OR_PAID_USAGE", "partial", partial,
                        "prometheus", queries, "dashboardPanelQueries", panelQueries, "exposition", bridge.scrape());
                Path result = Path.of("build/issue68/" + (dashboardCapture ? "dashboard-monitoring-" : "hold-monitoring-") + System.currentTimeMillis() + ".json");
                Files.writeString(result, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(artifact),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                System.out.println("ISSUE68_FAKE_MONITORING_RESULT=" + result);
                if (dashboardCapture) {
                    // 명시적 opt-in에서만 Context 종료 후 frozen exposition을 UI 검증 동안 제공한다.
                    int seconds = Integer.parseInt(System.getenv().getOrDefault("ISSUE68_DASHBOARD_HOLD_SECONDS", "180"));
                    assertThat(seconds).isBetween(30, 300);
                    System.out.println("ISSUE68_DASHBOARD_READY=" + java.time.Instant.now() + "; HOLD_SECONDS=" + seconds);
                    long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
                    while (System.nanoTime() < until && !Thread.currentThread().isInterrupted()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(250_000_000);
                    }
                }
            }
        } finally {
            if (discovery) Files.writeString(targets, "[]\n");
        }
    }

    private static Issue68EvaluationContract.Case caseById(String id) {
        return Issue68EvaluationContract.heldout().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static JsonNode query(String prometheus, String expression) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(prometheus + "/api/v1/query?query="
                + java.net.URLEncoder.encode(expression, java.nio.charset.StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JSON.readTree(response.body());
        assertThat(json.path("status").asText()).isEqualTo("success");
        return json;
    }

    private static double value(String prometheus, String expression) throws Exception {
        var result = query(prometheus, expression).path("data").path("result");
        return result.isEmpty() ? -1 : Double.parseDouble(result.get(0).path("value").get(1).asText());
    }
}
