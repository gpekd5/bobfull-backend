package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.application.service.SplitMessageCandidateGate;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.infrastructure.ai.Issue68EvaluationContract;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.infrastructure.ai.Issue68OpenAiContext;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.common.config.JpaAuditingConfig;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 기존 Production Listener/Adapter/Repository를 호출하고 관측만 추가한다. Kafka/DB 소유권은 fixture에 있다.
final class Issue68OpenAiKafkaHarness {
    private static final Map<String, Capture> CAPTURES = new ConcurrentHashMap<>();

    private Issue68OpenAiKafkaHarness() {
    }

    record Sample(String caseId, ModerationResult actual, String status, boolean completed, int partition, long offset,
                  long receivedNs, long flushNs, long committedNs, long returnedNs, long ackNs, int deliveries,
                  Long promptTokens, Long totalTokens, String errorCode, String provider, String model,
                  String promptVersion, String policyVersion) {
    }

    record Lag(long elapsedNs, long value) {
    }

    record Result(String mode, List<Sample> samples, List<List<String>> polls, List<Lag> lag,
                  long finalLag, int finalRows, int transactionViolations, int unsafeCommits,
                  int saveFailures, int dltRecords, List<String> gateReasons,
                  Issue68EvaluationContract.Quality quality, Map<String, Double> latencyMs, int commitObservations) {
    }

    record ExpandedExecution(Issue68ExpandedPlan.Unit unit,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> ownerSetup) { }

    static Result executeExpanded(String bootstrap, String jdbc, String username, String password,
            Issue68ExpandedPlan.Unit unit, Issue68HttpBoundary boundary, String endpoint,
            java.util.function.Consumer<AssertableApplicationContext> setup,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> ownerSetup) {
        boundary.requireExpandedUnit(unit); // Live도 별도 Pilot 승인/현재 조건/동결 해시를 통과해야 한다.
        return execute(bootstrap, jdbc, username, password, unit.mode().batchEnabled(),
                Issue68ExpandedPlan.cases(unit.stage()), false, boundary, endpoint, setup, 3,
                new ExpandedExecution(unit, ownerSetup));
    }

    static void requireNextStage(Result nativeResult, Result batchResult, List<Issue68EvaluationContract.Case> cases) {
        List<String> reasons = new ArrayList<>(nativeResult.gateReasons());
        reasons.addAll(batchResult.gateReasons());
        Map<String, Sample> nativeById = new LinkedHashMap<>();
        nativeResult.samples().forEach(s -> nativeById.put(s.caseId(), s));
        Map<String, Sample> batchById = new LinkedHashMap<>();
        batchResult.samples().forEach(s -> batchById.put(s.caseId(), s));
        for (var c : cases) {
            reasons.addAll(Issue68EvaluationContract.pairedGate(c, nativeById.get(c.id()).actual(), batchById.get(c.id()).actual()));
        }
        if (!reasons.isEmpty()) {
            throw new IllegalStateException("ISSUE68_HUMAN_REVIEW_REQUIRED:" + reasons.stream().distinct().sorted().toList());
        }
    }

    static Result execute(String bootstrap, String jdbc, String username, String password, boolean batch,
            List<Issue68EvaluationContract.Case> cases, boolean sequential, Issue68HttpBoundary boundary,
            String endpoint, java.util.function.Consumer<AssertableApplicationContext> setup) {
        return execute(bootstrap, jdbc, username, password, batch, cases, sequential, boundary, endpoint, setup, 3);
    }

    static Result execute(String bootstrap, String jdbc, String username, String password, boolean batch,
            List<Issue68EvaluationContract.Case> cases, boolean sequential, Issue68HttpBoundary boundary,
            String endpoint, java.util.function.Consumer<AssertableApplicationContext> setup, int sendPartitions) {
        return execute(bootstrap, jdbc, username, password, batch, cases, sequential, boundary, endpoint, setup, sendPartitions, null);
    }

    private static Result execute(String bootstrap, String jdbc, String username, String password, boolean batch,
            List<Issue68EvaluationContract.Case> cases, boolean sequential, Issue68HttpBoundary boundary,
            String endpoint, java.util.function.Consumer<AssertableApplicationContext> setup, int sendPartitions,
            ExpandedExecution expanded) {
        // 외부 개발 DB URL을 실수로 받지 않도록 fixture가 전달한 loopback/임의 포트만 허용한다.
        if (!jdbc.matches("jdbc:mysql://localhost:[0-9]+/issue68.*")
                && !jdbc.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/issue68.*")) {
            throw new IllegalArgumentException("ISSUE68_NON_FIXTURE_DB");
        }
        if (sendPartitions < 1 || sendPartitions > 3) {
            throw new IllegalArgumentException("ISSUE68_INVALID_PARTITION_INPUT");
        }
        String key = UUID.randomUUID().toString();
        Instant executionStartedAt = Instant.now();
        Capture capture = new Capture(cases, () -> {
            if (boundary.o0()) {
                boundary.stop();
            }
        });
        CAPTURES.put(key, capture);
        String topic = "issue68-" + key;
        String group = topic + "-group";
        String dlt = topic + "-dlt";
        List<Result> results = new ArrayList<>();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1), new NewTopic(dlt, 3, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
            runner(batch, bootstrap, jdbc, username, password, boundary, endpoint, key, topic, group, dlt)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        setup.accept(context);
                        var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                        assertThat(registry.getListenerContainers()).hasSize(1);
                        var container = registry.getListenerContainers().iterator().next();
                        SizeTimeBatchOwnerLoop owner = null;
                        Thread ownerThread = null;
                        try {
                            var repository = context.getBean(ChatMessageRepository.class);
                            int ordinal = 0;
                            for (var c : cases) {
                                // 원본 Dataset은 독립 메시지다. 서로 다른 room/sender로 Split 문맥을 만들지 않는다.
                                long synthetic = ++ordinal;
                                var row = repository.saveAndFlush(ChatMessage.create(synthetic, synthetic, synthetic, c.content()));
                                capture.points.put(row.getId(), new Point(c, synthetic));
                            }
                            if (sequential) {
                                container.start();
                                await().atMost(Duration.ofSeconds(20)).until(() -> container.getAssignedPartitions() != null
                                        && container.getAssignedPartitions().size() == 3);
                            }
                            if (boundary.a1N1()) {
                                boundary.beforeExchange(() -> {
                                    if (capture.lastPoll == 0 || System.nanoTime() - capture.lastPoll
                                            > capture.maxPollIntervalNs - Duration.ofSeconds(65).toNanos()) {
                                        throw new IllegalStateException("ISSUE68_POLL_LEASE_HOLD");
                                    }
                                });
                            }
                            if (expanded != null) {
                                boundary.beforeExchange(() -> {
                                    // 다음 HTTP 60초와 종료 여유 5초를 직전 poll의 lease 안에 확보한다.
                                    if (capture.lastPoll == 0 || System.nanoTime() - capture.lastPoll
                                            > capture.maxPollIntervalNs - Duration.ofSeconds(65).toNanos()) {
                                        throw new IllegalStateException("ISSUE68_POLL_LEASE_HOLD");
                                    }
                                });
                                if (expanded.unit().mode().owner()) {
                                    @SuppressWarnings("unchecked")
                                    var factory = (org.springframework.kafka.core.ConsumerFactory<Object, Object>) context
                                            .getBean(org.springframework.kafka.core.ConsumerFactory.class);
                                    owner = new SizeTimeBatchOwnerLoop(() -> factory.createConsumer(group, null, "-issue68-owner"),
                                            topic, context.getBean(ChatModerationBatchService.class),
                                            context.getBean(ChatModerationBatchRetryProcessor.class),
                                            context.getBean("chatModerationBatchErrorHandler", org.springframework.kafka.listener.DefaultErrorHandler.class),
                                            context.getBean(com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics.class),
                                            expanded.unit().mode().waitMs, 1000);
                                    owner.haltRequested = boundary::o0Held;
                                    owner.commitFailure = failure -> {
                                        boundary.checkpoint("owner-commit-failed", Map.of("error", failure.getClass().getSimpleName()));
                                        boundary.stop();
                                    };
                                    owner.executionFailure = failure -> {
                                        boundary.checkpoint("owner-execution-failed", Map.of("error", failure.getClass().getSimpleName()));
                                        boundary.stop();
                                    };
                                    expanded.ownerSetup().accept(owner);
                                    var startedOwner = owner;
                                    ownerThread = new Thread(() -> {
                                        startedOwner.run();
                                        if (startedOwner.failure != null) boundary.stop();
                                    }, "issue68-owner");
                                    ownerThread.start();
                                    await().atMost(Duration.ofSeconds(20)).until(() -> startedOwner.assigned == 3 || boundary.o0Held());
                                } else {
                                    container.start();
                                    await().atMost(Duration.ofSeconds(20)).until(() -> container.getAssignedPartitions() != null
                                            && container.getAssignedPartitions().size() == 3);
                                }
                            }
                            @SuppressWarnings("unchecked")
                            var producer = (KafkaOperations<Object, Object>) context.getBean(KafkaOperations.class);
                            ordinal = 0;
                            var sampler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                            sampler.scheduleAtFixedRate(() -> {
                                try {
                                    capture.lag.add(new Lag(System.nanoTime() - capture.started, lag(admin, topic, group)));
                                } catch (Exception error) {
                                    capture.lagErrors.add(error.getClass().getSimpleName());
                                    capture.stopOnTechnical.run();
                                }
                            }, 0, 100, TimeUnit.MILLISECONDS);
                            try {
                                long publishStart = System.nanoTime();
                                List<java.util.concurrent.CompletableFuture<?>> acknowledgements = new ArrayList<>();
                                for (var entry : capture.points.entrySet()) {
                                    if (expanded != null) {
                                        long deadline = publishStart + Duration.ofMillis((long) ordinal * expanded.unit().traffic().intervalMs).toNanos();
                                        while (!boundary.o0Held() && System.nanoTime() < deadline) {
                                            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(1_000_000L, deadline - System.nanoTime()));
                                        }
                                        if (boundary.o0Held()) break;
                                    }
                                    int partition = ordinal++ % sendPartitions;
                                    Point point = entry.getValue();
                                    point.targetPublished = expanded == null ? 0 : publishStart
                                            + Duration.ofMillis((long) (ordinal - 1) * expanded.unit().traffic().intervalMs).toNanos();
                                    point.published = System.nanoTime();
                                    var future = producer.send(topic, partition, "synthetic", new ChatMessageCreatedEvent(
                                            "fixture-event", 1, entry.getKey(), point.syntheticRoom(), Instant.now()))
                                            .whenComplete((value, failure) -> {
                                                if (failure != null) {
                                                    capture.stopOnTechnical.run();
                                                    return;
                                                }
                                                point.ack = System.nanoTime();
                                                point.partition = partition;
                                                point.offset = value.getRecordMetadata().offset();
                                            });
                                    if (expanded == null) future.get(10, TimeUnit.SECONDS);
                                    else acknowledgements.add(future);
                                    if (sequential) {
                                        await().atMost(Duration.ofSeconds(boundary.live() ? 280 : 30))
                                                .until(() -> point.returned > 0 || boundary.o0Held());
                                        if (boundary.o0Held()) {
                                            throw new IllegalStateException("ISSUE68_O0_EXECUTION_GATE_HOLD");
                                        }
                                        await().atMost(Duration.ofSeconds(10)).until(() -> lag(admin, topic, group) == 0);
                                    }
                                }
                                for (var ack : acknowledgements) ack.get(10, TimeUnit.SECONDS);
                                if (!sequential && expanded == null) {
                                    container.start();
                                }
                                await().atMost(Duration.ofSeconds(boundary.live() ? 280 : 60)).until(() -> boundary.o0Held()
                                        || capture.points.values().stream().allMatch(p -> p.returned > 0));
                                if (boundary.o0Held()) {
                                    throw new IllegalStateException("ISSUE68_O0_EXECUTION_GATE_HOLD");
                                }
                                await().atMost(Duration.ofSeconds(15)).until(() -> {
                                    long lag = lag(admin, topic, group);
                                    capture.lag.add(new Lag(System.nanoTime() - capture.started, lag));
                                    return lag == 0;
                                });
                            } finally {
                                if (boundary.live() && capture.points.values().stream().anyMatch(p -> p.returned == 0)) {
                                    boundary.stop();
                                }
                                sampler.shutdown();
                                if (!sampler.awaitTermination(15, TimeUnit.SECONDS)) {
                                    sampler.shutdownNow();
                                    capture.lagErrors.add("SamplerTerminationTimeout");
                                }
                            }
                            var moderations = context.getBean(ChatModerationRepository.class);
                            List<Sample> samples = new ArrayList<>();
                            List<String> gates = new ArrayList<>();
                            for (var entry : capture.points.entrySet()) {
                                var p = entry.getValue();
                                var db = moderations.findByMessageId(entry.getKey()).orElseThrow();
                                ModerationResult actual = db.isCompleted()
                                        ? new ModerationResult(db.getResult(), db.getCategories(), db.getRiskLevel()) : null;
                                samples.add(new Sample(p.testCase.id(), actual, db.getStatus().name(), db.isCompleted(),
                                        p.partition, p.offset, p.received, p.flush, p.committed, p.returned, p.ack,
                                        p.deliveries.get(), db.getPromptTokens(), db.getTotalTokens(), db.getErrorCode(),
                                        db.getProvider(), db.getModel(), db.getPromptVersion(), db.getPolicyVersion()));
                                gates.addAll(Issue68EvaluationContract.gate(p.testCase, actual, db.isCompleted()));
                                if (boundary.live() && actual != null
                                        && p.testCase.expected().result() == com.bobfull.chat.domain.entity.ModerationResultType.SAFE
                                        && actual.result() == com.bobfull.chat.domain.entity.ModerationResultType.FLAGGED) {
                                    gates.add(batch ? "FP_BATCH" : "FP_NATIVE");
                                }
                                if (db.isCompleted() && "OpenAI".equals(db.getProvider())
                                        && !"gpt-4o-mini-2024-07-18".equals(db.getModel())) {
                                    gates.add("RESPONSE_MODEL_CHANGED");
                                }
                            }
                            if (capture.violations.get() != 0 || capture.unsafeCommits.get() != 0) {
                                gates.add("DB_OFFSET_BOUNDARY");
                            }
                            if (!capture.lagErrors.isEmpty()) {
                                gates.add("LAG_OBSERVATION_FAILED:" + capture.lagErrors.stream().distinct().sorted().toList());
                            }
                            if (boundary.attempts().stream().anyMatch(a -> a.usage() == null)) {
                                gates.add("USAGE_UNKNOWN");
                            }
                            var meters = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
                            boolean failuresObserved = java.util.stream.Stream.of("bobfull.ai.moderation.failure",
                                    "bobfull.ai.moderation.batch.failure").flatMap(name -> meters.find(name).counters().stream())
                                    .anyMatch(counter -> counter.count() > 0);
                            if (failuresObserved || capture.saveFailures.get() > 0
                                    || boundary.calls().stream().anyMatch(call -> call.failed)) {
                                // 재처리 후 DB가 정상이어도 앞서 발생한 기술 오류를 다음 평가 단계에서 숨기지 않는다.
                                gates.add("TECHNICAL_FAILURE_OBSERVED");
                            }
                            results.add(new Result(expanded == null ? batch ? "poll" : "native" : expanded.unit().mode().name(), samples, List.copyOf(capture.polls),
                                    List.copyOf(capture.lag), lag(admin, topic, group), (int) moderations.count(),
                                    capture.violations.get(), capture.unsafeCommits.get(), capture.saveFailures.get(),
                                    dltCount(admin, dlt), gates.stream().distinct().sorted().toList(),
                                    Issue68EvaluationContract.quality(cases, samples.stream().filter(Sample::completed)
                                            .collect(java.util.stream.Collectors.toMap(Sample::caseId, Sample::actual))),
                                    latencies(samples), capture.commits.get()));
                            boundary.checkpoint("completed-" + (batch ? "batch" : "native"), results.get(0));
                        } finally {
                            try {
                                stopOwner(owner, ownerThread, boundary);
                            } finally {
                                try {
                                    container.stop();
                                } finally {
                            // 실패/취소 경로도 DB 완료 시점, Offset 위치, Lag를 가능한 범위에서 남긴다.
                            Map<String, Object> partial = new LinkedHashMap<>();
                            partial.put("startedAtUtc", executionStartedAt.toString());
                            partial.put("endedAtUtc", Instant.now().toString());
                            partial.put("clock", "monotonic ns within this JVM; wall clock UTC is separate");
                            partial.put("cases", capture.points.entrySet().stream().map(entry -> {
                                var p = entry.getValue();
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("caseId", p.testCase.id());
                                row.put("partition", p.partition);
                                row.put("offset", p.offset);
                                row.put("receivedNs", p.received);
                                row.put("flushNs", p.flush);
                                row.put("ackNs", p.ack);
                                row.put("dbCommittedNs", p.committed);
                                row.put("returnedNs", p.returned);
                                row.put("deliveries", p.deliveries.get());
                                if (p.received > 0 && p.committed > 0) row.put("receivedToCommitMs", (p.committed - p.received) / 1e6);
                                try {
                                    context.getBean(ChatModerationRepository.class).findByMessageId(entry.getKey()).ifPresent(db -> {
                                        row.put("status", db.getStatus().name());
                                        row.put("actual", db.isCompleted() ? new ModerationResult(db.getResult(), db.getCategories(), db.getRiskLevel()) : null);
                                        row.put("v1v2", com.bobfull.chat.infrastructure.ai.Issue68GateV2.evaluate(p.testCase,
                                                db.isCompleted() ? new ModerationResult(db.getResult(), db.getCategories(), db.getRiskLevel()) : null,
                                                db.isCompleted()));
                                        row.put("provider", db.getProvider());
                                        row.put("model", db.getModel());
                                        row.put("promptTokens", db.getPromptTokens());
                                        row.put("totalTokens", db.getTotalTokens());
                                        row.put("promptVersion", db.getPromptVersion());
                                        row.put("policyVersion", db.getPolicyVersion());
                                    });
                                } catch (RuntimeException unavailable) {
                                    row.put("dbSnapshot", "UNAVAILABLE"); // 예외 본문/원문/실제 ID를 저장하지 않는다.
                                }
                                return row;
                            }).toList());
                            partial.put("httpAttempts", boundary.attempts());
                            partial.put("sdkUsage", boundary.sdkEntries());
                            partial.put("logicalCalls", boundary.calls());
                            partial.put("executionHeld", boundary.o0Held());
                            var partialMeters = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
                            partial.put("meters", partialMeters.getMeters().stream().map(meter -> Map.of(
                                    "name", meter.getId().getName(), "type", meter.getId().getType().name(),
                                    "tags", meter.getId().getTags().stream().map(t -> Map.of("key", t.getKey(), "value", t.getValue())).toList(),
                                    "measurements", java.util.stream.StreamSupport.stream(meter.measure().spliterator(), false)
                                            .map(m -> Map.of("statistic", m.getStatistic().name(), "value", m.getValue())).toList())).toList());
                            boundary.snapshotMetrics(partialMeters); // Context 종료 후에도 Fake exporter가 마지막 값을 제공한다.
                            partial.put("polls", List.copyOf(capture.polls));
                            if (expanded != null) {
                                partial.put("unit", expanded.unit());
                                partial.put("publication", capture.points.values().stream().map(p -> Map.of(
                                        "caseId", p.testCase.id(), "targetNs", p.targetPublished,
                                        "publishedNs", p.published, "ackNs", p.ack)).toList());
                                partial.put("consumer", Map.of("partitions", 3, "concurrency", 1, "maxPollRecords", 10,
                                        "maxPollIntervalMs", capture.maxPollIntervalNs / 1_000_000));
                            }
                            partial.put("unsafeCommits", capture.unsafeCommits.get());
                            try {
                                partial.put("lag", lag(admin, topic, group));
                                partial.put("committedOffsets", admin.listConsumerGroupOffsets(group)
                                        .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).entrySet().stream()
                                        .map(e -> Map.of("partition", e.getKey().partition(), "offset", e.getValue().offset())).toList());
                                partial.put("dltCount", dltCount(admin, dlt));
                            } catch (Exception unavailable) {
                                partial.put("offsetSnapshot", "UNAVAILABLE");
                            }
                            boundary.checkpoint("partial-" + (batch ? "batch" : "native"), partial);
                            }
                            }
                        }
                    });
        } catch (Exception failure) {
            throw new IllegalStateException("ISSUE68_STAGE_INCOMPLETE", failure);
        } finally {
            CAPTURES.remove(key);
        }
        return results.get(0);
    }

    private static void stopOwner(SizeTimeBatchOwnerLoop owner, Thread thread, Issue68HttpBoundary boundary)
            throws InterruptedException {
        if (owner == null) return;
        owner.stop = true;
        thread.join(75_000);
        if (thread.isAlive()) {
            boundary.stop();
            throw new IllegalStateException("ISSUE68_OWNER_TERMINATION_TIMEOUT");
        }
        boundary.checkpoint("owner-state", Map.of("flushes", List.copyOf(owner.flushes),
                "maxBuffered", owner.maxBuffered, "threadViolations", owner.threadViolations,
                "retryCount", owner.retryCount, "commits", List.copyOf(owner.commits),
                "failure", owner.failure == null ? "NONE" : owner.failure.getClass().getSimpleName()));
        if (owner.failure != null || owner.threadViolations != 0 || owner.maxBuffered > 20) {
            boundary.stop();
            throw new IllegalStateException("ISSUE68_OWNER_SAFETY_HOLD");
        }
    }

    private static Map<String, Double> latencies(List<Sample> samples) {
        List<Double> received = samples.stream().filter(s -> s.committedNs() > 0)
                .map(s -> (s.committedNs() - s.receivedNs()) / 1e6).toList();
        List<Double> ack = samples.stream().filter(s -> s.committedNs() > 0)
                .map(s -> (s.committedNs() - s.ackNs()) / 1e6).toList();
        return Map.of("receivedToCommitP50", PollBatchMeasurements.percentile(received, .5),
                "receivedToCommitP95", PollBatchMeasurements.percentile(received, .95),
                "publishAckToCommitP50", PollBatchMeasurements.percentile(ack, .5),
                "publishAckToCommitP95", PollBatchMeasurements.percentile(ack, .95));
    }

    private static ApplicationContextRunner runner(boolean batch, String bootstrap, String jdbc, String username,
            String password, Issue68HttpBoundary boundary, String endpoint, String key, String topic, String group, String dlt) {
        return Issue68OpenAiContext.runner(boundary, endpoint, batch)
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                        TransactionAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean("issue68Instrumentation", BeanPostProcessor.class, () -> instrumentation(CAPTURES.get(key), boundary))
                .withUserConfiguration(PersistenceConfiguration.class, JpaAuditingConfig.class, ChatModerationService.class,
                        ChatModerationBatchService.class, ModerationRulePolicy.class, SplitMessageCandidateGate.class,
                        BusinessMetricRecorder.class, ChatModerationConsumerErrorHandlingConfig.class,
                        ChatModerationBatchConfig.class, ChatModerationDltRecoverer.class,
                        ChatModerationConsumer.class, ChatModerationBatchConsumer.class)
                .withPropertyValues("spring.datasource.url=" + jdbc, "spring.datasource.username=" + username,
                        "spring.datasource.password=" + password, "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.kafka.bootstrap-servers=" + bootstrap,
                        "spring.kafka.consumer.group-id=" + group, "spring.kafka.consumer.auto-offset-reset=earliest",
                        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
                        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.bobfull.chat.application.event",
                        "spring.kafka.consumer.properties.interceptor.classes=" + PollObserver.class.getName(),
                        "spring.kafka.consumer.properties.issue68.capture=" + key,
                        "spring.kafka.consumer.max-poll-records=10", "spring.kafka.consumer.enable-auto-commit=false",
                        "spring.kafka.consumer.fetch-min-size=1B", "spring.kafka.consumer.fetch-max-wait=100ms",
                        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
                        "spring.kafka.producer.acks=all", "spring.kafka.producer.properties.enable.idempotence=true",
                        "spring.kafka.producer.properties.linger.ms=0", "spring.kafka.listener.auto-startup=false",
                        "spring.kafka.listener.ack-mode=record", "spring.kafka.listener.poll-timeout=100ms",
                        "bobfull.kafka.chat-message.topic=" + topic, "bobfull.kafka.chat-message.dlt-topic=" + dlt,
                        "bobfull.kafka.chat-message.consumer-enabled=true", "bobfull.kafka.chat-message.consumer-concurrency=1");
    }

    private static BeanPostProcessor instrumentation(Capture capture, Issue68HttpBoundary boundary) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (boundary.o0() && bean instanceof org.springframework.kafka.listener.CommonErrorHandler) {
                    // Commit/poll 등 record 밖 오류 통지를 관측만 하고 기존 Handler에 그대로 위임한다.
                    ProxyFactory observed = new ProxyFactory(bean);
                    observed.setProxyTargetClass(true);
                    observed.addAdvice((MethodInterceptor) call -> {
                        if (call.getMethod().getName().equals("handleOtherException")) {
                            capture.stopOnTechnical.run();
                        }
                        return call.proceed();
                    });
                    return observed.getProxy();
                }
                if (bean instanceof ChatModerationRepository && bean instanceof Advised advised) {
                    int transaction = -1;
                    for (int i = 0; i < advised.getAdvisors().length; i++) {
                        if (advised.getAdvisors()[i].getAdvice() instanceof TransactionInterceptor) {
                            transaction = i;
                        }
                    }
                    assertThat(transaction).isGreaterThanOrEqualTo(0);
                    advised.addAdvice(transaction + 1, (MethodInterceptor) call -> {
                        if (!call.getMethod().getName().equals("saveAndFlush")) {
                            return call.proceed();
                        }
                        var row = (ChatModeration) call.getArguments()[0];
                        var point = capture.points.get(row.getMessageId());
                        if (point == null) {
                            throw new IllegalStateException("ISSUE68_UNKNOWN_DB_RESULT");
                        }
                        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                            capture.violations.incrementAndGet();
                            capture.stopOnTechnical.run();
                        }
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                point.committed = System.nanoTime();
                                boundary.committedResult(point.testCase, row.isCompleted()
                                        ? new ModerationResult(row.getResult(), row.getCategories(), row.getRiskLevel()) : null,
                                        row.isCompleted());
                                boundary.checkpoint("db-progress", capture.points.values().stream().map(p -> Map.of(
                                        "caseId", p.testCase.id(), "partition", p.partition, "offset", p.offset,
                                        "dbCommittedNs", p.committed)).toList());
                            }
                        });
                        Object value = call.proceed();
                        point.flush = System.nanoTime();
                        return value;
                    });
                    advised.addAdvice(0, (MethodInterceptor) call -> {
                        if (!call.getMethod().getName().equals("saveAndFlush")) {
                            return call.proceed();
                        }
                        if (TransactionSynchronizationManager.isActualTransactionActive()) {
                            capture.violations.incrementAndGet();
                            capture.stopOnTechnical.run();
                        }
                        try {
                            Object result = call.proceed();
                            var row = (ChatModeration) call.getArguments()[0];
                            capture.points.get(row.getMessageId()).returned = System.nanoTime();
                            return result;
                        } catch (Throwable failure) {
                            capture.saveFailures.incrementAndGet();
                            capture.stopOnTechnical.run();
                            throw failure;
                        }
                    });
                }
                if (bean instanceof AiModerationPort || bean instanceof AiModerationBatchPort) {
                    boolean batch = bean instanceof AiModerationBatchPort;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        if (!call.getMethod().getName().equals("analyze")) {
                            return call.proceed();
                        }
                        List<String> contents;
                        if (batch) {
                            @SuppressWarnings("unchecked")
                            var items = (List<AiModerationBatchPort.Item>) call.getArguments()[0];
                            contents = items.stream().map(AiModerationBatchPort.Item::content).toList();
                        } else {
                            contents = List.of((String) call.getArguments()[0]);
                        }
                        var ids = contents.stream().map(c -> capture.byContent.get(c).id()).toList();
                        var logical = boundary.begin(ids, batch);
                        boolean failed = true;
                        try {
                            Object result = call.proceed();
                            failed = false;
                            return result;
                        } catch (Throwable failure) {
                            if (boundary.live()) {
                                throw new IllegalStateException("ISSUE68_LIVE_PORT_FAILED_REDACTED");
                            }
                            throw failure;
                        } finally {
                            boundary.end(logical, failed);
                        }
                    });
                    return proxy.getProxy();
                }
                return bean;
            }
        };
    }

    private static long lag(AdminClient admin, String topic, String group) throws Exception {
        long result = 0;
        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetSpec> offsets = new LinkedHashMap<>();
        for (int p = 0; p < 3; p++) {
            offsets.put(new TopicPartition(topic, p), OffsetSpec.latest());
        }
        var end = admin.listOffsets(offsets).all().get(10, TimeUnit.SECONDS);
        for (var partition : offsets.keySet()) {
            result += end.get(partition).offset() - (committed.get(partition) == null ? 0 : committed.get(partition).offset());
        }
        return result;
    }

    private static int dltCount(AdminClient admin, String topic) throws Exception {
        Map<TopicPartition, OffsetSpec> offsets = new LinkedHashMap<>();
        for (int p = 0; p < 3; p++) {
            offsets.put(new TopicPartition(topic, p), OffsetSpec.latest());
        }
        return (int) admin.listOffsets(offsets).all().get(10, TimeUnit.SECONDS).values().stream().mapToLong(x -> x.offset()).sum();
    }

    public static final class PollObserver implements ConsumerInterceptor<Object, Object> {
        private Capture capture;

        @Override
        public void configure(Map<String, ?> config) {
            capture = CAPTURES.get(config.get("issue68.capture").toString());
            var actual = new ConsumerConfig(new java.util.HashMap<>(config));
            assertThat(actual.getInt(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(10);
            assertThat(actual.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
            capture.maxPollIntervalNs = Duration.ofMillis(actual.getInt(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).toNanos();
        }

        @Override
        public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
            long received = System.nanoTime();
            capture.lastPoll = received;
            List<String> ids = new ArrayList<>();
            for (var record : records) {
                if (record.value() instanceof ChatMessageCreatedEvent event) {
                    Point p = capture.points.get(event.messageId());
                    if (p == null) {
                        capture.stopOnTechnical.run();
                        throw new IllegalStateException("ISSUE68_UNKNOWN_KAFKA_EVENT");
                    }
                    if (p.received == 0) {
                        p.received = received;
                    }
                    p.partition = record.partition();
                    p.offset = record.offset();
                    p.deliveries.incrementAndGet();
                    ids.add(p.testCase.id());
                }
            }
            if (!ids.isEmpty()) {
                capture.polls.add(List.copyOf(ids));
            }
            return records;
        }

        @Override
        public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
            capture.commits.incrementAndGet();
            offsets.forEach((partition, offset) -> capture.points.values().stream()
                    .filter(p -> p.partition == partition.partition() && p.offset >= 0 && p.offset < offset.offset())
                    .filter(p -> p.committed == 0).forEach(p -> capture.unsafeCommits.incrementAndGet()));
            if (capture.unsafeCommits.get() > 0) {
                capture.stopOnTechnical.run();
            }
        }

        @Override
        public void close() {
        }
    }

    private static final class Capture {
        final Map<Long, Point> points = new LinkedHashMap<>();
        final Map<String, Issue68EvaluationContract.Case> byContent = new LinkedHashMap<>();
        final List<List<String>> polls = new CopyOnWriteArrayList<>();
        final List<Lag> lag = new CopyOnWriteArrayList<>();
        final AtomicInteger violations = new AtomicInteger();
        final AtomicInteger unsafeCommits = new AtomicInteger();
        final AtomicInteger saveFailures = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final List<String> lagErrors = new CopyOnWriteArrayList<>();
        final long started = System.nanoTime();
        volatile long lastPoll;
        volatile long maxPollIntervalNs;
        final Runnable stopOnTechnical;

        Capture(List<Issue68EvaluationContract.Case> cases, Runnable stopOnTechnical) {
            this.stopOnTechnical = stopOnTechnical;
            cases.forEach(c -> byContent.put(c.content(), c));
        }
    }

    private static final class Point {
        final Issue68EvaluationContract.Case testCase;
        final long room;
        final AtomicInteger deliveries = new AtomicInteger();
        volatile long received;
        volatile long flush;
        volatile long committed;
        volatile long returned;
        volatile long ack;
        volatile long published;
        volatile long targetPublished;
        volatile int partition = -1;
        volatile long offset = -1;

        Point(Issue68EvaluationContract.Case testCase, long room) {
            this.testCase = testCase;
            this.room = room;
        }

        long syntheticRoom() {
            return room;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {ChatMessage.class, ChatModeration.class})
    @EnableJpaRepositories(basePackageClasses = {ChatMessageRepository.class, ChatModerationRepository.class})
    static class PersistenceConfiguration {
    }
}
