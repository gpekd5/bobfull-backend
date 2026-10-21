package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.kafka.ChatModerationPollBatchMeasurementTest.Mode;
import com.bobfull.chat.infrastructure.kafka.ChatModerationPollBatchMeasurementTest.Scenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

// 정상 경로의 측정만 수행한다. 안전성 suite PASS 뒤 전용 task로 실행하고 과거 raw는 덮어쓰지 않는다.
@Tag("kafka-evidence")
@Testcontainers
class SizeTimeBatchComparisonMeasurementTest {
    @Container
    static final org.testcontainers.kafka.ConfluentKafkaContainer KAFKA =
            new org.testcontainers.kafka.ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    private static final JsonMapper JSON = new JsonMapper();
    private static final List<Scenario> SCENARIOS = List.of(new Scenario("low", 8, 250),
            new Scenario("interval", 24, 40), new Scenario("burst", 40, 0), new Scenario("sustained", 60, 5));

    @Test
    void 여섯조건을_네패턴으로_세번씩_비교하여_원본과_반복편차를_보존한다() throws Exception {
        // given
        List<Variant> variants = List.of(new Variant("native", false, -1), new Variant("poll", true, -1),
                new Variant("time0", true, 0), new Variant("time20", true, 20),
                new Variant("time50", true, 50), new Variant("time100", true, 100));
        List<Map<String, Object>> results = new ArrayList<>();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(variants.stream().flatMap(v -> java.util.stream.Stream.of(
                    new NewTopic(v.mode.topic, 1, (short) 1), new NewTopic(v.mode.dlt, 1, (short) 1))).toList())
                    .all().get(10, TimeUnit.SECONDS);
            contexts(variants, 0, () -> {
                try {
                    for (Variant variant : variants) {
                        variant.start();
                        ChatModerationPollBatchMeasurementTest.execute(variant.mode, new Scenario("warmup", 10, 0), 0, admin);
                    }
                    // when: 실행 순서를 고정 회전/역전한다. 모든 조건은 동일한 이전 입력 수를 가진다.
                    for (int scenarioIndex = 0; scenarioIndex < SCENARIOS.size(); scenarioIndex++) {
                        Scenario scenario = SCENARIOS.get(scenarioIndex);
                        for (int repeat = 1; repeat <= 3; repeat++) {
                            List<Variant> order = new ArrayList<>(variants);
                            java.util.Collections.rotate(order, scenarioIndex + repeat - 1);
                            if (repeat == 2) java.util.Collections.reverse(order);
                            for (Variant variant : order) {
                                int beforeFlushes = variant.owner == null ? 0 : variant.owner.flushes.size();
                                if (variant.owner != null) variant.owner.maxBuffered = variant.owner.buffered;
                                var run = ChatModerationPollBatchMeasurementTest.execute(variant.mode, scenario, repeat, admin);
                                Map<String, Object> row = new LinkedHashMap<>();
                                var summary = new LinkedHashMap<>(run.summary());
                                Map<String, Object> raw = JSON.readValue(JSON.writeValueAsString(run), LinkedHashMap.class);
                                var config = new LinkedHashMap<>(run.consumerConfig());
                                if (variant.owner != null) {
                                    assertThat(variant.owner.failure).isNull();
                                    assertThat(variant.owner.threadViolations).isZero();
                                    assertThat(variant.owner.maxBuffered).isLessThanOrEqualTo(20);
                                    var flushes = List.copyOf(variant.owner.flushes.subList(beforeFlushes, variant.owner.flushes.size()));
                                    var waits = flushes.stream().flatMap(flush -> flush.waitsNs().stream())
                                            .map(ns -> ns / 1_000_000.0).toList();
                                    summary.put("bufferWaitP50Ms", waits.isEmpty() ? null : PollBatchMeasurements.percentile(waits, .5));
                                    summary.put("bufferWaitP95Ms", waits.isEmpty() ? null : PollBatchMeasurements.percentile(waits, .95));
                                    summary.put("maxBufferedRecords", variant.owner.maxBuffered);
                                    raw.put("flushes", flushes);
                                    config.put("ackMode", "OWNER_CONTIGUOUS_COMMIT_SYNC");
                                    config.put("pollTimeoutPolicy", "min(100ms, oldest ready deadline remaining)");
                                    config.put("maxWaitMs", variant.waitMs);
                                    config.put("bufferLimitRecords", 20);
                                } else {
                                    summary.put("bufferWaitP50Ms", null);
                                    summary.put("bufferWaitP95Ms", null);
                                    summary.put("maxBufferedRecords", null);
                                }
                                raw.put("consumerConfig", config);
                                row.put("summary", summary);
                                row.put("raw", raw);
                                results.add(row);
                                System.out.println("ISSUE65_SUMMARY " + JSON.writeValueAsString(summary));
                            }
                        }
                    }
                    // then
                    assertThat(results).hasSize(72);
                    assertThat(results.stream().mapToInt(row -> ((Number) ((Map<?, ?>) row.get("summary")).get("messages")).intValue()).sum())
                            .isEqualTo(2376);
                    write(results);
                } catch (Exception exception) {
                    throw new IllegalStateException("ISSUE65_COMPARISON_FAILED", exception);
                } finally {
                    variants.forEach(Variant::stop);
                }
            });
        } finally {
            variants.forEach(v -> ChatModerationPollBatchMeasurementTest.MODES.remove(v.mode.name));
        }
    }

    private static void contexts(List<Variant> variants, int index, Runnable experiment) {
        if (index == variants.size()) {
            experiment.run();
            return;
        }
        Variant variant = variants.get(index);
        ChatModerationPollBatchMeasurementTest.MODES.put(variant.mode.name, variant.mode);
        ChatModerationPollBatchMeasurementTest.runner(variant.mode, KAFKA.getBootstrapServers()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(org.springframework.ai.chat.client.ChatClient.class)).isEmpty();
            variant.mode.context = context;
            contexts(variants, index + 1, experiment);
        });
    }

    private static void write(List<Map<String, Object>> results) throws Exception {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("executedAt", Instant.now().toString());
        var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        output.put("executionHead", new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip());
        assertThat(git.waitFor()).isZero();
        output.put("executionState", "uncommitted Issue65 source snapshot");
        var hashes = new LinkedHashMap<String, String>();
        for (String file : List.of(
                "src/main/java/com/bobfull/chat/application/service/ChatModerationBatchService.java",
                "src/main/java/com/bobfull/chat/infrastructure/kafka/ChatModerationBatchRetryProcessor.java",
                "src/test/java/com/bobfull/chat/infrastructure/kafka/SizeTimeBatchOwnerLoop.java",
                "src/test/java/com/bobfull/chat/infrastructure/kafka/SizeTimeBatchComparisonMeasurementTest.java",
                "src/test/java/com/bobfull/chat/infrastructure/kafka/ChatModerationPollBatchMeasurementTest.java",
                "src/test/java/com/bobfull/chat/infrastructure/kafka/PollBatchMeasurements.java")) {
            hashes.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file)))));
        }
        output.put("sourceSha256", hashes);
        output.put("fakeRequestLatencyMs", 60);
        output.put("warmupMessagesPerCondition", 10);
        output.put("quantileMethod", "nearest rank from raw samples, not mean of percentiles");
        output.put("receiveBoundary", "ConsumerInterceptor.onConsume: first receive per record, same timestamp within poll");
        output.put("completionBoundary", "JPA transaction synchronization afterCommit; not flush or Kafka offset commit");
        output.put("historicalBaseline", "Issue62 raw preserved; controls remeasured with Issue64 retry and Issue65 seam refactor");
        output.put("limitations", "local Kafka7.7.1/H2, six contexts with idle consumers, synchronous owner vs Spring Listener; no OpenAI/token/cost/quality claim");
        output.put("runs", results);
        Path path = Path.of("build/issue65/size-time-results.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        System.out.println("ISSUE65_RAW " + path + " runs=72 messages=2376");
    }

    private static final class Variant {
        final Mode mode;
        final long waitMs;
        SizeTimeBatchOwnerLoop owner;
        Thread thread;

        Variant(String name, boolean batch, long waitMs) {
            this.mode = new Mode("65-" + name, batch);
            this.waitMs = waitMs;
        }

        @SuppressWarnings("unchecked")
        void start() {
            if (waitMs < 0) {
                mode.container().start();
                await().atMost(Duration.ofSeconds(20)).until(() -> mode.container().getAssignedPartitions() != null
                        && mode.container().getAssignedPartitions().size() == 1);
            } else {
                ConsumerFactory<Object, Object> factory = mode.context.getBean(ConsumerFactory.class);
                owner = new SizeTimeBatchOwnerLoop(() -> factory.createConsumer(mode.group, null, "-owner"), mode.topic,
                        mode.context.getBean(ChatModerationBatchService.class), mode.context.getBean(ChatModerationBatchRetryProcessor.class),
                        mode.context.getBean("chatModerationBatchErrorHandler", DefaultErrorHandler.class),
                        mode.context.getBean(ChatModerationBatchMetrics.class), waitMs, 1000);
                thread = new Thread(owner, "issue65-" + mode.name);
                thread.start();
                await().atMost(Duration.ofSeconds(20)).until(() -> owner.assigned == 1);
            }
        }

        void stop() {
            if (owner == null) {
                if (mode.context != null) mode.container().stop();
                return;
            }
            owner.stop = true;
            try { thread.join(15_000); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            assertThat(thread.isAlive()).isFalse();
            assertThat(owner.failure).isNull();
        }
    }
}
