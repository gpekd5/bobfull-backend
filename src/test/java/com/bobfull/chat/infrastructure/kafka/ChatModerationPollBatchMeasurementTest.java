package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.application.service.SplitMessageCandidateGate;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.infrastructure.ai.FakeAiModerationAdapter;
import com.bobfull.chat.infrastructure.ai.FakeAiModerationBatchAdapter;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.common.config.JpaAuditingConfig;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.json.JsonMapper;

// 환경 의존 실측은 기본 회귀와 분리해 kafkaEvidenceTest --tests 로 명시 실행한다.
@Tag("kafka-evidence")
@Testcontainers
class ChatModerationPollBatchMeasurementTest {
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    private static final Map<String, Mode> MODES = new ConcurrentHashMap<>();
    private static final JsonMapper JSON = new JsonMapper();
    private static final List<Scenario> SCENARIOS = List.of(new Scenario("low", 8, 250),
            new Scenario("interval", 24, 40), new Scenario("burst", 40, 0), new Scenario("sustained", 60, 5));

    @Test
    void 네_유입패턴을_동일조건에서_각각_세번_측정하고_실제_commit과_Lag를_확인한다() throws Exception {
        // given
        var nativeMode = new Mode("native", false);
        var batchMode = new Mode("batch", true);
        MODES.put(nativeMode.name, nativeMode);
        MODES.put(batchMode.name, batchMode);
        List<PollBatchMeasurements.Run> results = new ArrayList<>();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(nativeMode.topic, 1, (short) 1),
                    new NewTopic(batchMode.topic, 1, (short) 1), new NewTopic(nativeMode.dlt, 1, (short) 1),
                    new NewTopic(batchMode.dlt, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            runner(nativeMode).run(nativeContext -> runner(batchMode).run(batchContext -> {
                assertThat(nativeContext).hasNotFailed();
                assertThat(batchContext).hasNotFailed();
                nativeMode.context = nativeContext;
                batchMode.context = batchContext;
                for (Mode mode : List.of(nativeMode, batchMode)) {
                    assertThat(mode.context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()).hasSize(1);
                    assertThat(mode.context.getBeansOfType(org.springframework.ai.chat.client.ChatClient.class)).isEmpty();
                    mode.container().start();
                    await().atMost(Duration.ofSeconds(20)).until(() -> mode.container().getAssignedPartitions() != null
                            && mode.container().getAssignedPartitions().size() == 1);
                    execute(mode, new Scenario("warmup", 10, 0), 0, admin);
                }
                // when: 모드 순서를 교차하고 각 반복에 같은 입력·주입 스케줄을 사용한다.
                for (Scenario scenario : SCENARIOS) {
                    for (int repeat = 1; repeat <= 3; repeat++) {
                        List<Mode> order = repeat % 2 == 1 ? List.of(nativeMode, batchMode) : List.of(batchMode, nativeMode);
                        for (Mode mode : order) {
                            var result = execute(mode, scenario, repeat, admin);
                            results.add(result);
                            System.out.println("ISSUE62_SUMMARY " + JSON.writeValueAsString(result.summary()));
                        }
                    }
                }
                // then: raw 표본과 summary를 별도 보존. 실제 DB id/원문은 출력하지 않는다.
                assertThat(results).hasSize(24);
                writeResults(results);
            }));
        } finally {
            MODES.clear();
        }
    }

    private static PollBatchMeasurements.Run execute(Mode mode, Scenario scenario, int repeat, AdminClient admin) {
        try {
            var messages = mode.context.getBean(ChatMessageRepository.class);
            var repository = mode.context.getBean(ChatModerationRepository.class);
            Map<Long, Point> points = new LinkedHashMap<>();
            // 같은 합성 message 순서/Rule 비중. 서로 다른 DB id는 결과에 노출하지 않는다.
            for (int ordinal = 1; ordinal <= scenario.count; ordinal++) {
                boolean rule = ordinal % 5 == 0 && repeat != 0;
                var row = messages.saveAndFlush(ChatMessage.create(1L, (long) ordinal, (long) ordinal,
                        rule ? "씨발" : "식사 일정 함께 확인해요"));
                points.put(row.getId(), new Point(ordinal, rule, row.getCreatedAt()));
            }
            var run = new Capture(points);
            var registry = mode.context.getBean(MeterRegistry.class);
            double pollsBefore = summaryCount(registry, "bobfull.ai.moderation.poll.size");
            double batchesBefore = summaryCount(registry, "bobfull.ai.moderation.batch.size");
            double processedBefore = counterTotal(registry, "bobfull.ai.moderation.processing");
            mode.active = run;
            var sampler = Executors.newSingleThreadScheduledExecutor();
            List<String> lagErrors = new CopyOnWriteArrayList<>();
            sampleLag(mode, run, admin);
            sampler.scheduleAtFixedRate(() -> {
                try {
                    sampleLag(mode, run, admin);
                } catch (Exception exception) {
                    lagErrors.add(exception.getClass().getSimpleName());
                }
            }, 100, 100, TimeUnit.MILLISECONDS);
            try {
                @SuppressWarnings("unchecked")
                var kafka = (KafkaOperations<Object, Object>) mode.context.getBean(KafkaOperations.class);
                List<java.util.concurrent.CompletableFuture<?>> acknowledgements = new ArrayList<>();
                long firstSend = System.nanoTime();
                for (var entry : points.entrySet()) {
                    Point point = entry.getValue();
                    long deadline = firstSend + TimeUnit.MILLISECONDS.toNanos((long) (point.ordinal - 1) * scenario.intervalMs);
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) {
                        TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                    var future = kafka.send(mode.topic, 0, "measurement", new ChatMessageCreatedEvent(
                            "synthetic-" + point.ordinal, 1, entry.getKey(), 1L, Instant.now()));
                    acknowledgements.add(future.whenComplete((value, failure) -> point.ack = System.nanoTime()));
                }
                java.util.concurrent.CompletableFuture.allOf(acknowledgements.toArray(java.util.concurrent.CompletableFuture[]::new))
                        .get(10, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(30)).until(() -> points.values().stream().allMatch(p -> p.returned > 0));
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    sampleLag(mode, run, admin);
                    return run.lag.get(run.lag.size() - 1).value() == 0;
                });
                // Repository 반환 뒤 별도 조회로 실제 committed row를 확인한다.
                var finalRows = repository.findByMessageIdIn(points.keySet());
                assertThat(finalRows).hasSize(scenario.count).allMatch(ChatModeration::isCompleted);
                assertThat(lagErrors).isEmpty();
                assertThat(run.violations.get()).isZero();
                assertThat(run.failures.get()).isZero();
                assertThat(run.saveFailures.get()).isZero();
                assertThat(run.requests).noneMatch(PollBatchMeasurements.Request::failed);
                assertThat(run.polls.stream().mapToInt(Integer::intValue).sum()).isEqualTo(scenario.count);
                assertThat(run.requests.stream().mapToInt(PollBatchMeasurements.Request::size).sum())
                        .isEqualTo(points.values().stream().filter(p -> !p.rule).count());
                var samples = points.values().stream().map(point -> {
                    assertThat(point.received).isPositive();
                    assertThat(point.flush).isBetween(point.received, point.committed);
                    assertThat(point.returned).isGreaterThanOrEqualTo(point.committed);
                    return new PollBatchMeasurements.Sample(point.ordinal, point.rule, point.received - run.start,
                            point.flush - run.start, point.committed - run.start, point.returned - run.start,
                            point.ack - run.start, point.createdToCommitMs);
                }).toList();
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("batchPollCountDelta", summaryCount(registry, "bobfull.ai.moderation.poll.size") - pollsBefore);
                metrics.put("batchRequestCountDelta", summaryCount(registry, "bobfull.ai.moderation.batch.size") - batchesBefore);
                metrics.put("batchProcessedMessagesDelta", counterTotal(registry, "bobfull.ai.moderation.processing") - processedBefore);
                if (mode.batch) {
                    assertThat(metrics.get("batchPollCountDelta")).isEqualTo((double) run.polls.size());
                    assertThat(metrics.get("batchRequestCountDelta")).isEqualTo((double) run.batches.size());
                    assertThat(metrics.get("batchProcessedMessagesDelta")).isEqualTo((double) scenario.count);
                } else {
                    assertThat(metrics.values()).allMatch(value -> value.equals(0.0));
                    assertThat(registry.find("bobfull.ai.moderation.poll.size").summary()).isNull();
                    assertThat(registry.find("bobfull.ai.moderation.batch.size").summary()).isNull();
                    // Native OFF에는 Batch metric 자체가 없으므로 관측된 0처럼 출력하지 않는다.
                    metrics.replaceAll((key, value) -> null);
                }
                return new PollBatchMeasurements.Run(scenario.name, mode.name, repeat, scenario.count, scenario.intervalMs,
                        List.copyOf(run.polls), run.emptyPolls.get(), List.copyOf(run.batches), List.copyOf(run.requests), samples,
                        List.copyOf(run.lag), run.failures.get(), run.saveFailures.get(), 0, finalRows.size(), run.violations.get(),
                        PollBatchMeasurements.millis(points.values().stream().mapToLong(p -> p.committed).max().orElseThrow()
                                - points.values().stream().mapToLong(p -> p.ack).max().orElseThrow()), metrics, mode.config);
            } finally {
                sampler.shutdownNow();
                sampler.awaitTermination(5, TimeUnit.SECONDS);
                mode.active = null;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("MEASUREMENT_FAILED " + mode.name + "/" + scenario.name + "/" + repeat, exception);
        }
    }

    private static void sampleLag(Mode mode, Capture run, AdminClient admin) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(mode.group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        long committed = offsets.containsKey(mode.partition) ? offsets.get(mode.partition).offset() : 0;
        // committed 먼저, end 나중: 두 Admin RPC는 원자적 snapshot이 아니며 peak는 관측 표본이다.
        long end = admin.listOffsets(Map.of(mode.partition, OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS)
                .get(mode.partition).offset();
        run.lag.add(new PollBatchMeasurements.Lag(System.nanoTime() - run.start, end, committed));
    }

    private static double summaryCount(MeterRegistry registry, String name) {
        var summary = registry.find(name).summary();
        return summary == null ? 0 : summary.count();
    }

    private static double counterTotal(MeterRegistry registry, String name) {
        return registry.find(name).counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private static void writeResults(List<PollBatchMeasurements.Run> results) {
        try {
            var hashes = new LinkedHashMap<String, String>();
            for (String filename : List.of("ChatModerationPollBatchMeasurementTest.java", "PollBatchMeasurements.java",
                    "PollBatchMeasurementsTest.java")) {
                var file = Path.of("src/test/java/com/bobfull/chat/infrastructure/kafka/" + filename);
                hashes.put(filename, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
            var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
            String head = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            assertThat(git.waitFor()).isZero();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("executedAt", Instant.now().toString());
            output.put("head", head);
            output.put("measurementSourceSha256", hashes);
            output.put("fakeLatencyMs", 60);
            output.put("warmupMessagesPerMode", 10);
            output.put("quantileMethod", "nearest-rank over original message samples; never mean of percentiles");
            output.put("kafka", "confluentinc/cp-kafka:7.7.1 / partition1 / concurrency1 / max.poll.records10");
            output.put("database", "isolated H2 MODE=MySQL per mode; create-drop");
            output.put("receiveBoundary", "ConsumerInterceptor.onConsume after deserialization, before poll returns");
            output.put("completionBoundary", "TransactionSynchronization.afterCommit inside actual repository transaction");
            output.put("runs", results.stream().map(run -> Map.of("summary", run.summary(), "raw", run)).toList());
            Path file = Path.of("build/issue62/poll-batch-results.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            System.out.println("ISSUE62_RAW " + file + " runs=" + results.size());
        } catch (Exception exception) {
            throw new IllegalStateException("MEASUREMENT_RESULT_WRITE_FAILED", exception);
        }
    }

    private static ApplicationContextRunner runner(Mode mode) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                        TransactionAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("measurementInstrumentation", BeanPostProcessor.class, () -> instrumentation(mode))
                .withUserConfiguration(PersistenceConfiguration.class, JpaAuditingConfig.class, ChatModerationService.class,
                        ChatModerationBatchService.class, ModerationRulePolicy.class, SplitMessageCandidateGate.class,
                        ChatModerationMetrics.class, ChatModerationBatchMetrics.class, BusinessMetricRecorder.class,
                        FakeAiModerationAdapter.class, FakeAiModerationBatchAdapter.class,
                        ChatModerationConsumerErrorHandlingConfig.class, ChatModerationBatchConfig.class,
                        ChatModerationDltRecoverer.class, ChatModerationConsumer.class, ChatModerationBatchConsumer.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:issue62-" + mode.name + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "spring.kafka.consumer.group-id=" + mode.group, "spring.kafka.consumer.auto-offset-reset=earliest",
                        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
                        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.bobfull.chat.application.event",
                        "spring.kafka.consumer.properties.interceptor.classes=" + PollObserver.class.getName(),
                        "spring.kafka.consumer.properties.measurement.mode=" + mode.name,
                        "spring.kafka.consumer.max-poll-records=10", "spring.kafka.consumer.enable-auto-commit=false",
                        "spring.kafka.consumer.fetch-min-size=1B", "spring.kafka.consumer.fetch-max-wait=100ms",
                        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
                        "spring.kafka.producer.acks=all", "spring.kafka.producer.properties.enable.idempotence=true",
                        "spring.kafka.producer.properties.linger.ms=0", "spring.kafka.listener.auto-startup=false",
                        "spring.kafka.listener.ack-mode=record", "spring.kafka.listener.poll-timeout=100ms",
                        "bobfull.kafka.chat-message.topic=" + mode.topic, "bobfull.kafka.chat-message.dlt-topic=" + mode.dlt,
                        "bobfull.kafka.chat-message.consumer-enabled=true", "bobfull.kafka.chat-message.consumer-concurrency=1",
                        "bobfull.kafka.chat-message.batch-enabled=" + mode.batch,
                        "bobfull.ai.moderation.fake-enabled=true", "bobfull.ai.moderation.fake-result-type=SAFE",
                        "bobfull.ai.moderation.fake-latency-ms=60");
    }

    private static BeanPostProcessor instrumentation(Mode mode) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof ChatModerationRepository && bean instanceof Advised advised) {
                    int transactionIndex = -1;
                    var advisors = advised.getAdvisors();
                    for (int index = 0; index < advisors.length; index++) {
                        if (advisors[index].getAdvice() instanceof TransactionInterceptor) {
                            transactionIndex = index;
                        }
                    }
                    assertThat(transactionIndex).as("실제 Repository transaction advisor 필요").isGreaterThanOrEqualTo(0);
                    advised.addAdvice(transactionIndex + 1, (MethodInterceptor) invocation -> {
                        Capture run = mode.active;
                        if (run == null || !invocation.getMethod().getName().equals("saveAndFlush")
                                || !(invocation.getArguments()[0] instanceof ChatModeration row)) {
                            return invocation.proceed();
                        }
                        Point point = run.points.get(row.getMessageId());
                        if (point == null) {
                            return invocation.proceed();
                        }
                        if (!TransactionSynchronizationManager.isActualTransactionActive()
                                || !TransactionSynchronizationManager.isSynchronizationActive()) {
                            run.violations.incrementAndGet();
                        } else {
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    point.committed = System.nanoTime();
                                    point.createdToCommitMs = Duration.between(point.createdAt, Instant.now()).toNanos() / 1_000_000.0;
                                }
                            });
                        }
                        Object result = invocation.proceed();
                        point.flush = System.nanoTime();
                        return result;
                    });
                    advised.addAdvice(0, (MethodInterceptor) invocation -> {
                        Capture run = mode.active;
                        if (run == null || !invocation.getMethod().getName().equals("saveAndFlush")
                                || !(invocation.getArguments()[0] instanceof ChatModeration row)) {
                            return invocation.proceed();
                        }
                        if (TransactionSynchronizationManager.isActualTransactionActive()) {
                            run.violations.incrementAndGet();
                        }
                        try {
                            Object result = invocation.proceed();
                            Point point = run.points.get(row.getMessageId());
                            if (point != null) {
                                point.returned = System.nanoTime();
                            }
                            return result;
                        } catch (Throwable failure) {
                            run.saveFailures.incrementAndGet();
                            throw failure;
                        }
                    });
                }
                if (bean instanceof AiModerationPort || bean instanceof AiModerationBatchPort) {
                    boolean batch = bean instanceof AiModerationBatchPort;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        Capture run = mode.active;
                        if (run == null || !invocation.getMethod().getName().equals("analyze")) {
                            return invocation.proceed();
                        }
                        int size = batch ? ((List<?>) invocation.getArguments()[0]).size() : 1;
                        if (batch) {
                            run.batches.add(size);
                        }
                        long started = System.nanoTime();
                        boolean failed = true;
                        try {
                            Object result = invocation.proceed();
                            failed = false;
                            return result;
                        } finally {
                            run.requests.add(new PollBatchMeasurements.Request(batch ? "batch" : "native", size,
                                    started - run.start, System.nanoTime() - run.start, failed));
                        }
                    });
                    return proxy.getProxy();
                }
                if (bean instanceof DefaultErrorHandler handler) {
                    handler.setRetryListeners(new RetryListener() {
                        @Override
                        public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {
                            if (mode.active != null) {
                                mode.active.failures.incrementAndGet();
                            }
                        }
                    });
                }
                return bean;
            }
        };
    }

    // Kafka가 생성하는 테스트 전용 interceptor. filter/순서/내용은 바꾸지 않는다.
    public static class PollObserver implements ConsumerInterceptor<Object, Object> {
        private Mode mode;

        @Override
        public void configure(Map<String, ?> config) {
            mode = MODES.get(config.get("measurement.mode").toString());
            var resolved = new ConsumerConfig(new java.util.HashMap<>(config));
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String key : List.of("max.poll.records", "enable.auto.commit", "fetch.min.bytes", "fetch.max.wait.ms",
                    "max.poll.interval.ms", "auto.offset.reset")) {
                safe.put(key, resolved.values().get(key));
            }
            assertThat(resolved.getInt(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(10);
            assertThat(resolved.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
            assertThat(resolved.getInt(ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo(1);
            assertThat(resolved.getInt(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo(100);
            safe.put("concurrency", 1);
            safe.put("partitions", 1);
            safe.put("pollTimeoutMs", 100);
            safe.put("ackMode", mode.batch ? "BATCH" : "RECORD");
            mode.config = safe;
        }

        @Override
        public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
            Capture run = mode.active;
            if (run != null) {
                if (records.isEmpty()) {
                    run.emptyPolls.incrementAndGet();
                } else {
                    long received = System.nanoTime();
                    run.polls.add(records.count());
                    records.forEach(record -> {
                        var event = (ChatMessageCreatedEvent) record.value();
                        Point point = run.points.get(event.messageId());
                        if (point != null && point.received == 0) {
                            point.received = received;
                        }
                    });
                }
            }
            return records;
        }

        @Override public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) { }
        @Override public void close() { }
    }

    private record Scenario(String name, int count, int intervalMs) { }

    private static final class Point {
        final int ordinal;
        final boolean rule;
        final Instant createdAt;
        volatile long received, flush, committed, returned, ack;
        volatile double createdToCommitMs;

        Point(int ordinal, boolean rule, Instant createdAt) {
            this.ordinal = ordinal;
            this.rule = rule;
            this.createdAt = createdAt;
        }
    }

    private static final class Capture {
        final long start = System.nanoTime();
        final Map<Long, Point> points;
        final List<Integer> polls = new CopyOnWriteArrayList<>();
        final List<Integer> batches = new CopyOnWriteArrayList<>();
        final List<PollBatchMeasurements.Request> requests = new CopyOnWriteArrayList<>();
        final List<PollBatchMeasurements.Lag> lag = new CopyOnWriteArrayList<>();
        final AtomicInteger emptyPolls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger saveFailures = new AtomicInteger();
        final AtomicInteger violations = new AtomicInteger();

        Capture(Map<Long, Point> points) {
            this.points = Map.copyOf(points);
        }
    }

    private static final class Mode {
        final String name;
        final boolean batch;
        final String topic;
        final String dlt;
        final String group;
        final TopicPartition partition;
        volatile Capture active;
        volatile Map<String, Object> config;
        AssertableApplicationContext context;

        Mode(String name, boolean batch) {
            this.name = name;
            this.batch = batch;
            this.topic = "issue62-" + name;
            this.dlt = topic + ".dlt";
            this.group = topic + "-group";
            this.partition = new TopicPartition(topic, 0);
        }

        org.springframework.kafka.listener.MessageListenerContainer container() {
            return context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().iterator().next();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ChatMessage.class)
    @EnableJpaRepositories(basePackageClasses = ChatMessageRepository.class)
    static class PersistenceConfiguration { }
}
