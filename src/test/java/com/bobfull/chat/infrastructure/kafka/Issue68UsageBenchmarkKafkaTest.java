package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.infrastructure.ai.Issue68Budget;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import com.bobfull.chat.infrastructure.ai.Issue68FakeOpenAiEndpoint;
import com.bobfull.chat.infrastructure.ai.Issue68MonitoringBridge;
import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkPlan;
import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkSafetyTest;
import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class Issue68UsageBenchmarkKafkaTest {
    @Container static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only").withEnv("MYSQL_DATABASE", "issue68_benchmark")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    private static final List<Map<String, Object>> RUNS = new java.util.concurrent.CopyOnWriteArrayList<>();
    @TempDir Path temporary;

    @Test
    void Fake480건은_기존HOLD를_보존하며_여섯조건_네패턴의_Usage와_Commit을_측정한다() throws Exception {
        Path root = Issue68UsageBenchmarkSafetyTest.priorFixture(temporary);
        String prior = Files.readString(root.resolve("budget.jsonl"));
        int records = 0;
        for (var stage : Issue68UsageBenchmarkPlan.Stage.values()) {
            try (var session = Issue68UsageBenchmarkSession.rehearsal(root, stage);
                    var endpoint = new Issue68FakeOpenAiEndpoint()) {
                endpoint.gzip(true);
                endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.SEMANTIC_SAFE); // 의도적 FN/Critical 실패. 품질 PASS가 아니다.
                endpoint.beforeResponse(() -> java.util.concurrent.locks.LockSupport.parkNanos(60_000_000));
                for (var unit : Issue68UsageBenchmarkPlan.units(stage)) {
                    session.plan().start(unit);
                    try (var boundary = session.rehearsalBoundary(endpoint.uri()); var bridge = new Issue68MonitoringBridge(0)) {
                        boundary.fakeMonitoring(bridge::registry, bridge::freeze);
                        var report = Issue68UsageBenchmarkExecution.execute(KAFKA.getBootstrapServers(), jdbc(), session,
                                unit, boundary, endpoint.uri().toString(), owner -> { });
                        var result = (Issue68OpenAiKafkaHarness.Result) report.get("result");
                        assertThat(report.get("technicalOk")).isEqualTo(true);
                        assertThat(result.finalRows()).isEqualTo(20);
                        assertThat(result.finalLag()).isZero();
                        assertThat(result.quality().fn()).isPositive();
                        assertThat(result.gateReasons()).isNotEmpty();
                        assertThat(session.plan().stopped()).isFalse();
                        assertThat(boundary.calls().stream().flatMap(c -> c.caseIds.stream())).hasSize(20).doesNotHaveDuplicates();
                        assertThat(boundary.attempts()).hasSize(boundary.calls().size());
                        assertThat(boundary.calls()).allSatisfy(c -> assertThat(c.cap).isEqualTo(128 * c.caseIds.size()));
                        assertThat(result.samples()).filteredOn(s -> boundary.calls().stream().anyMatch(c -> c.batch && c.caseIds.contains(s.caseId())))
                                .allSatisfy(s -> assertThat(s.promptTokens()).isNull());
                        // 기존 패널 이름/단가식과 같은 metric. Context 종료 후에도 동결 exporter가 응답한다.
                        String scrape = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://127.0.0.1:" + bridge.port() + "/metrics")).build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                        assertThat(scrape).contains("gen_ai_client_operation_seconds_count", "gen_ai_client_token_usage_total",
                                "gen_ai_client_operation_seconds_bucket", "harness_scope=\"issue68_fake\"");
                        assertThat(scrape).doesNotContain("issue68_test_gen_ai", "messageId=", "memberId=", "chatRoomId=");
                        assertThat(meterSum(scrape, "gen_ai_client_operation_seconds_count", "")).isEqualTo(boundary.calls().size());
                        assertThat(meterSum(scrape, "gen_ai_client_token_usage_total", "gen_ai_token_type=\"input\""))
                                .isEqualTo(900L * boundary.calls().size());
                        assertThat(meterSum(scrape, "gen_ai_client_token_usage_total", "gen_ai_token_type=\"output\""))
                                .isEqualTo(400);
                        RUNS.add(Map.of("scope", "FREE_FAKE_BENCHMARK_NOT_MODEL_QUALITY_OR_REAL_COST", "run", report,
                                "metricScrape", scrape, "burden", session.budget().chargedNanos()));
                        records += result.finalRows();
                    }
                }
                assertThat(session.plan().complete()).isTrue();
                session.completeStage();
                assertThatThrownBy(() -> session.budget().reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
            }
        }
        assertThat(records).isEqualTo(480);
        assertThat(Files.readString(root.resolve("budget.jsonl"))).startsWith(prior);
        try (var budget = Issue68Budget.auditBenchmark(root.resolve("budget.jsonl"))) {
            assertThat(budget.chargedNanos()).isGreaterThan(19_881_750L).isLessThan(1_000_000_000L);
            assertThat(budget.benchmarkChargedNanos()).isLessThan(500_000_000L);
            assertThat(budget.blocked()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Issue68FakeOpenAiEndpoint.Mode.class, names = {"NO_USAGE", "RATE_LIMIT", "RETRY_KNOWN_USAGE", "MAPPING", "VALIDATION", "PARSE", "TRUNCATED"})
    void Owner의_기술오류는_부분결과를_보존하고_다음HTTP를_차단한다(Issue68FakeOpenAiEndpoint.Mode mode) throws Exception {
        fault(mode.name(), mode, owner -> { }, false);
    }

    @Test void Owner_Commit실패는_DB성공을_보존하고_다음HTTP를_차단한다() throws Exception {
        fault("COMMIT", null, owner -> owner.beforeCommit = offsets -> {
            throw new org.apache.kafka.clients.consumer.CommitFailedException("fixture");
        }, false);
    }

    @Test void Owner_예산부족은_실제HTTP_0회에서_차단한다() throws Exception {
        fault("BUDGET", null, owner -> { }, true);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DB", "OFFSET"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void Native의_DB_Commit오류도_다음HTTP를_차단한다(String fault) throws Exception {
        Path root = Issue68UsageBenchmarkSafetyTest.priorFixture(temporary);
        try (var session = Issue68UsageBenchmarkSession.rehearsal(root, Issue68UsageBenchmarkPlan.Stage.BURST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            var unit = Issue68UsageBenchmarkPlan.units(session.plan().stage()).stream()
                    .filter(u -> u.mode() == Issue68ExpandedPlan.Mode.NATIVE).findFirst().orElseThrow();
            prepareContractOnly(session.plan(), unit);
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                assertThatThrownBy(() -> Issue68UsageBenchmarkExecution.execute(KAFKA.getBootstrapServers(), jdbc(), session,
                        unit, boundary, endpoint.uri().toString(), context -> {
                            if (fault.equals("DB")) {
                                var repository = (org.springframework.aop.framework.Advised) context.getBean(
                                        com.bobfull.chat.infrastructure.repository.ChatModerationRepository.class);
                                repository.addAdvice(1, (org.aopalliance.intercept.MethodInterceptor) call -> {
                                    if (call.getMethod().getName().equals("saveAndFlush")) throw new IllegalStateException("fixture-db");
                                    return call.proceed();
                                });
                            } else {
                                var factory = (org.springframework.kafka.core.DefaultKafkaConsumerFactory) context
                                        .getBean(org.springframework.kafka.core.ConsumerFactory.class);
                                factory.addPostProcessor(consumer -> {
                                    ((org.springframework.aop.framework.Advised) consumer).addAdvice(1,
                                            (org.aopalliance.intercept.MethodInterceptor) call -> {
                                                if (call.getMethod().getName().startsWith("commit"))
                                                    throw new org.apache.kafka.clients.consumer.CommitFailedException("fixture-offset");
                                                return call.proceed();
                                            });
                                    return consumer;
                                });
                            }
                        }, owner -> { })).isInstanceOf(RuntimeException.class);
                assertThat(session.plan().stopped()).isTrue();
                assertThat(endpoint.received()).isEqualTo(1);
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false)).isInstanceOf(IllegalStateException.class);
                RUNS.add(Map.of("scope", "FAKE_NATIVE_TECHNICAL_STOP", "fault", fault, "http", boundary.attempts()));
            }
        }
    }

    private void fault(String name, Issue68FakeOpenAiEndpoint.Mode mode,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> ownerSetup, boolean exhaust) throws Exception {
        Path root = Issue68UsageBenchmarkSafetyTest.priorFixture(temporary);
        try (var session = Issue68UsageBenchmarkSession.rehearsal(root, Issue68UsageBenchmarkPlan.Stage.BURST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            var unit = Issue68UsageBenchmarkPlan.units(session.plan().stage()).stream()
                    .filter(u -> u.mode() == Issue68ExpandedPlan.Mode.TIME_100).findFirst().orElseThrow();
            prepareContractOnly(session.plan(), unit);
            if (mode != null) endpoint.defaultMode(mode);
            if (exhaust) for (int i = 0; i < 5; i++) session.budget().settle(session.budget().reserve(1280),
                    new Issue68Budget.Usage(128000, 1280, 129280, 0L));
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                assertThatThrownBy(() -> Issue68UsageBenchmarkExecution.execute(KAFKA.getBootstrapServers(), jdbc(),
                        session, unit, boundary, endpoint.uri().toString(), ownerSetup)).isInstanceOf(RuntimeException.class);
                int received = endpoint.received();
                assertThat(received).isEqualTo(exhaust ? 0 : 1);
                assertThat(session.plan().stopped()).isTrue();
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false)).isInstanceOf(IllegalStateException.class);
                assertThat(endpoint.received()).isEqualTo(received);
                assertThatThrownBy(session::completeStage).isInstanceOf(IllegalStateException.class);
                try (var paths = Files.walk(root)) {
                    var partial = paths.filter(p -> p.getFileName().toString().contains("-partial-")).findFirst().orElseThrow();
                    var snapshot = new JsonMapper().readTree(Files.readString(partial)).path("data");
                    if (name.equals("COMMIT")) {
                        assertThat(snapshot.path("cases").valueStream().filter(c -> c.path("dbCommittedNs").asLong() > 0).count()).isPositive();
                        assertThat(snapshot.path("lag").asLong()).isPositive();
                    }
                }
                RUNS.add(Map.of("scope", "FAKE_FAULT_PRIOR_UNITS_CONTRACT_ONLY_NOT_MEASURED", "fault", name,
                        "httpReceived", received, "logical", boundary.calls(), "http", boundary.attempts(),
                        "holds", session.plan().holds(), "burden", session.budget().chargedNanos()));
            }
        }
    }

    @Test
    void 기존Dashboard_네패널의_원본PromQL을_조건별_격리Prometheus에서_실제조회한다() throws Exception {
        Path root = Issue68UsageBenchmarkSafetyTest.priorFixture(temporary);
        var dashboard = new JsonMapper().readTree(Files.readString(Path.of(
                "ops/local-monitoring/grafana/provisioning/dashboards/bobfull-ai-moderation-observability.json")));
        try (var session = Issue68UsageBenchmarkSession.rehearsal(root, Issue68UsageBenchmarkPlan.Stage.BURST)) {
            for (var unit : Issue68UsageBenchmarkPlan.units(session.plan().stage())) {
                session.plan().start(unit);
                try (var bridge = new Issue68MonitoringBridge(18068); var endpoint = new Issue68FakeOpenAiEndpoint()) {
                    Path config = temporary.resolve("prometheus-" + unit.mode() + ".yml");
                    Files.writeString(config, "global:\n  scrape_interval: 200ms\nscrape_configs:\n  - job_name: issue68-benchmark-fake\n"
                            + "    metrics_path: /metrics\n    static_configs:\n      - targets: ['host.docker.internal:18068']\n");
                    // 조건마다 새 tmpfs TSDB. 기존 앱 8080/운영 Prometheus/datasource는 전혀 연결하지 않는다.
                    try (var prometheus = new GenericContainer<>("prom/prometheus:v3.14.0").withExposedPorts(9090)
                            .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(config), "/etc/prometheus/prometheus.yml")
                            .withTmpFs(Map.of("/prometheus", "rw,uid=65534,gid=65534,mode=0700")).waitingFor(Wait.forHttp("/-/ready"))) {
                        prometheus.start();
                        String url = "http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090);
                        var responses = new java.util.concurrent.atomic.AtomicInteger();
                        endpoint.beforeResponse(() -> {
                            if (responses.incrementAndGet() == 2) {
                                // count=1인 scrape를 확보하고 두 번째 완료를 만들어 rate/p95에도 증분을 남긴다.
                                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                                        scalar(url, "sum(gen_ai_client_operation_seconds_count)") == 1);
                            }
                        });
                        try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                            boundary.fakeMonitoring(bridge::registry, bridge::freeze);
                            var report = Issue68UsageBenchmarkExecution.execute(KAFKA.getBootstrapServers(), jdbc(), session,
                                    unit, boundary, endpoint.uri().toString(), owner -> { });
                            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                                    scalar(url, "sum(gen_ai_client_operation_seconds_count)") == boundary.calls().size());
                            var answers = new java.util.LinkedHashMap<String, Object>();
                            for (var panel : dashboard.path("panels")) {
                                if (!List.of(3, 4, 5, 12).contains(panel.path("id").asInt())) continue;
                                String expression = panel.path("targets").get(0).path("expr").asText();
                                var result = query(url, expression);
                                assertThat(result.path("status").asText()).isEqualTo("success");
                                assertThat(result.path("data").path("result").isEmpty()).isFalse();
                                assertThat(result.path("data").path("result").valueStream()).allSatisfy(item ->
                                        assertThat(Double.isFinite(Double.parseDouble(item.path("value").get(1).asText()))).isTrue());
                                answers.put(panel.path("title").asText(), Map.of("promql", expression, "response", result));
                            }
                            assertThat(answers).hasSize(4);
                            assertThat(scalar(url, "sum(gen_ai_client_token_usage_total{gen_ai_token_type=\"input\"})"))
                                    .isEqualTo(900L * boundary.calls().size());
                            RUNS.add(Map.of("scope", "FREE_ISOLATED_EXISTING_DASHBOARD_PROMQL_NOT_UI_SCREENSHOT_OR_REAL_USAGE",
                                    "unit", unit, "queries", answers, "run", report));
                        }
                    }
                }
            }
            session.completeStage();
        }
    }

    private static tools.jackson.databind.JsonNode query(String base, String expression) throws Exception {
        String url = base + "/api/v1/query?query=" + java.net.URLEncoder.encode(expression, java.nio.charset.StandardCharsets.UTF_8);
        String body = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        return new JsonMapper().readTree(body);
    }

    private static double scalar(String base, String expression) {
        try {
            var values = query(base, expression).path("data").path("result");
            return values.isEmpty() ? Double.NaN : Double.parseDouble(values.get(0).path("value").get(1).asText());
        } catch (Exception notReady) { return Double.NaN; }
    }

    private static void prepareContractOnly(Issue68UsageBenchmarkPlan plan, Issue68ExpandedPlan.Unit target) {
        for (var unit : Issue68UsageBenchmarkPlan.units(plan.stage())) {
            plan.start(unit);
            if (unit.equals(target)) return;
            for (var c : Issue68UsageBenchmarkPlan.cases()) { plan.begin(List.of(c.id()), false); plan.committed(c, c.expected(), true); }
            plan.completeUnit(true);
        }
    }

    private static String jdbc() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/issue68_benchmark?useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static double meterSum(String scrape, String metric, String label) {
        return scrape.lines().filter(line -> line.startsWith(metric + "{") && line.contains(label))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))).sum();
    }

    @AfterAll static void write() throws Exception {
        Files.createDirectories(Path.of("build/issue68"));
        Path path = Path.of("build/issue68/usage-benchmark-fake-" + Instant.now().toEpochMilli() + ".json");
        Files.writeString(path, new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "scope", "FREE_FAKE_ONLY_NO_PAID_APPROVAL_OR_QUALITY_PASS", "atUtc", Instant.now().toString(),
                "manifestSha256", Issue68UsageBenchmarkPlan.manifestSha256(Issue68UsageBenchmarkPlan.Stage.BURST), "runs", RUNS)),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("ISSUE68_BENCHMARK_FAKE_RAW=" + path);
    }
}
