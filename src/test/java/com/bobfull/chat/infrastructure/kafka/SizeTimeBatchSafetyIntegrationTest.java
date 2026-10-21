package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SizeTimeBatchSafetyIntegrationTest {
    @Container
    static final org.testcontainers.kafka.ConfluentKafkaContainer KAFKA =
            new org.testcontainers.kafka.ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");
    private static final ModerationResult SAFE = new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);

    @Test
    void 여러_poll을_수집하고_Rule_및_완료건을_제외하여_안전하게_commit한다() throws Exception {
        fixture("normal", f -> {
            var rows = f.rows(10);
            var rule = f.messages.saveAndFlush(ChatMessage.create(1L, 2L, 3L, "씨발"));
            f.moderation.analyze(rows.get(0).getId());
            f.send(List.of(rows.get(0), rule));
            var loop = f.start(100, false);
            for (int i = 1; i < rows.size(); i++) {
                f.send(List.of(rows.get(i)));
                Thread.sleep(5);
            }
            f.complete(11);
            assertThat(loop.maxBuffered).isLessThanOrEqualTo(20);
            assertThat(loop.flushes).isNotEmpty();
            assertThat(f.mode.config.get("max.poll.records")).isEqualTo(10);
            assertThat(f.requests.stream().mapToInt(List::size).sum() + f.nativeCalls.get()).isEqualTo(10);
            assertThat(loop.failure).isNull();
        });
    }

    @Test
    void 공유_Provider_실패는_두번만_요청하고_DLT_DB후_commit한다() throws Exception {
        fixture("shared", f -> {
            f.failProvider();
            f.send(f.rows(5));
            var loop = f.start(100, false);
            f.complete(5);
            assertThat(f.requests).hasSize(2);
            assertThat(f.nativeCalls.get()).isZero();
            assertThat(f.dlt()).isEqualTo(5);
            assertThat(f.results.findAll()).allMatch(row -> row.getStatus() == ModerationProcessingStatus.ANALYSIS_FAILED);
            assertThat(loop.pausedPolls).isPositive();
        });
    }

    @Test
    void 일부_Validation_실패는_기존_indexed_Retry로_격리한다() throws Exception {
        fixture("validation", f -> {
            f.outputs = items -> List.of(new AiModerationBatchPort.Output(items.get(0).id(), null),
                    new AiModerationBatchPort.Output(items.get(1).id(), SAFE));
            f.send(f.rows(2));
            f.start(20, false);
            f.complete(2);
            assertThat(f.requests).hasSize(1);
            assertThat(f.nativeCalls.get()).isEqualTo(1);
            assertThat(f.dlt()).isZero();
            assertThat(f.results.findAll()).allMatch(ChatModeration::isCompleted);
        });
    }

    @Test
    void DLT후_최종DB_실패는_Provider없이_recovery만_재시도한다() throws Exception {
        fixture("final-db", f -> {
            f.failProvider();
            var rows = f.rows(2);
            doThrow(new IllegalStateException("FINAL_DB_FAILURE")).doCallRealMethod()
                    .when(f.moderation).recordFinalFailure(eq(rows.get(0).getId()), anyString());
            f.send(rows);
            f.start(20, false);
            f.complete(2);
            assertThat(f.requests).hasSize(2);
            assertThat(f.dlt()).isEqualTo(3);
            assertThat(f.results.findAll()).allMatch(row -> row.getStatus() == ModerationProcessingStatus.ANALYSIS_FAILED);
        });
    }

    @Test
    void DB후_commit실패와_Shutdown은_재전달시_완료건을_다시_요청하지_않는다() throws Exception {
        fixture("commit", f -> {
            var loop = f.start(20, true);
            loop.beforeCommit = offsets -> { throw new org.apache.kafka.common.errors.TimeoutException("COMMIT_FAILURE"); };
            f.send(f.rows(2));
            await().atMost(Duration.ofSeconds(15)).until(() -> f.results.count() == 2);
            assertThat(f.offset()).isZero();
            f.stop();
            int calls = f.requests.size();
            f.start(20, true);
            f.complete(2);
            assertThat(f.requests).hasSize(calls);
            assertThat(f.nativeCalls.get()).isZero();
        });
    }

    @Test
    void Shutdown은_대기항목을_flush하지_않고_다음_Consumer가_재전달받는다() throws Exception {
        fixture("shutdown", f -> {
            var loop = f.start(10_000, true);
            f.send(f.rows(1));
            await().atMost(Duration.ofSeconds(10)).until(() -> loop.buffered == 1);
            f.stop();
            assertThat(f.nativeCalls.get()).isZero();
            assertThat(f.offset()).isZero();
            f.start(20, true);
            f.complete(1);
            assertThat(f.nativeCalls.get()).isEqualTo(1);
        });
    }

    @Test
    void Rule_후속완료는_앞의_미완료_offset을_넘지_않고_용량Flush로_상한을_지킨다() throws Exception {
        fixture("capacity", f -> {
            var rows = new ArrayList<>(f.rows(1));
            for (int i = 0; i < 19; i++) rows.add(f.messages.saveAndFlush(ChatMessage.create(1L, 2L, 3L, "씨발")));
            f.configure = loop -> loop.beforeExecution = records -> assertThat(f.offset()).isZero();
            f.send(rows);
            var loop = f.start(10_000, false);
            f.complete(20);
            assertThat(loop.maxBuffered).isEqualTo(20);
            assertThat(loop.flushes).anyMatch(flush -> flush.reason().equals("capacity"));
            assertThat(f.nativeCalls.get()).isEqualTo(1);
        });
    }

    @Test
    void deadline은_추가유입으로_연장하지_않고_요청은_시간도달시_부족해도_실행한다() throws Exception {
        fixture("deadline", f -> {
            f.start(100, true);
            var rows = f.rows(4);
            for (var row : rows) {
                f.send(List.of(row));
                Thread.sleep(40);
            }
            f.complete(4);
            assertThat(f.loop.flushes).allMatch(flush -> flush.reason().equals("deadline"));
            assertThat(f.loop.flushes.get(0).waitsNs().get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(100).toNanos());
            assertThat(f.loop.pollSizes.size()).isGreaterThan(1);
        });
    }

    @Test
    void 일부_DB저장실패도_정상결과를_보존하고_실패항목만_재시도한다() throws Exception {
        fixture("partial-db", f -> {
            var rows = f.rows(3);
            var first = new java.util.concurrent.atomic.AtomicBoolean(true);
            f.beforeSave = row -> {
                if (row.getMessageId().equals(rows.get(1).getId()) && first.getAndSet(false)) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("SAVE_FAILURE");
                }
            };
            f.send(rows);
            f.start(20, false);
            f.complete(3);
            assertThat(f.requests).hasSize(1);
            assertThat(f.nativeCalls.get()).isEqualTo(1);
            assertThat(f.dlt()).isZero();
        });
    }

    @Test
    void indexed_소진후_DB복구실패도_Provider없이_복구만_반복한다() throws Exception {
        fixture("indexed-db", f -> {
            var rows = f.rows(2);
            f.nativeResult = null;
            f.outputs = items -> List.of(new AiModerationBatchPort.Output(items.get(0).id(), null),
                    new AiModerationBatchPort.Output(items.get(1).id(), SAFE));
            doThrow(new IllegalStateException("FINAL_DB_FAILURE")).doCallRealMethod()
                    .when(f.moderation).recordFinalFailure(eq(rows.get(0).getId()), anyString());
            f.send(rows);
            f.start(20, false);
            f.complete(2);
            assertThat(f.requests).hasSize(1);
            assertThat(f.nativeCalls.get()).isEqualTo(1);
            assertThat(f.dlt()).isEqualTo(2);
            assertThat(f.results.findByMessageId(rows.get(0).getId()).orElseThrow().getStatus())
                    .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED);
        });
    }

    @Test
    void DLT발행실패시_DB를_선행하지_않고_Provider호출없이_복구한다() throws Exception {
        fixture("dlt-send", f -> {
            var rows = f.rows(2);
            f.failProvider();
            var sends = new AtomicInteger();
            doAnswer(call -> {
                var record = (org.apache.kafka.clients.producer.ProducerRecord<?, ?>) call.getArgument(0);
                if (record.topic().equals(f.mode.dlt) && sends.incrementAndGet() == 1) {
                    assertThat(f.offset()).isZero();
                    assertThat(f.results.findByMessageId(rows.get(0).getId())).isEmpty();
                    return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("DLT_SEND_FAILURE"));
                }
                return call.callRealMethod();
            }).when(f.recoveryKafka).send(org.mockito.ArgumentMatchers.<org.apache.kafka.clients.producer.ProducerRecord<Object, Object>>any());
            f.send(rows);
            f.start(20, false);
            f.complete(2);
            assertThat(f.requests).hasSize(2);
            assertThat(f.dlt()).isEqualTo(2);
            assertThat(sends).hasValue(3);
        });
    }

    @Test
    void 실제_rebalance는_대기항목을_강제flush하지_않고_재전달한다() throws Exception {
        fixture("rebalance", f -> {
            var loop = f.start(10_000, true);
            f.send(f.rows(1));
            await().atMost(Duration.ofSeconds(10)).until(() -> loop.buffered == 1);
            loop.rebalanceRequested = true;
            await().atMost(Duration.ofSeconds(10)).until(() -> loop.revokeCount > 0);
            assertThat(f.offset()).isZero();
            assertThat(f.nativeCalls.get()).isZero();
            f.stop();
            f.start(20, true);
            f.complete(1);
            assertThat(f.nativeCalls.get()).isEqualTo(1);
        });
    }

    @Test
    void Lost_통지주입시_commit없이_버퍼를_제거하고_재전달한다() throws Exception {
        fixture("lost", f -> {
            var loop = f.start(10_000, true);
            f.send(f.rows(1));
            await().atMost(Duration.ofSeconds(10)).until(() -> loop.buffered == 1);
            loop.injectLostNotification = true;
            await().atMost(Duration.ofSeconds(10)).until(() -> loop.lostCount == 1);
            f.stop();
            assertThat(f.offset()).isZero();
            assertThat(f.nativeCalls.get()).isZero();
            f.start(20, true);
            f.complete(1);
        });
    }

    @Test
    void 여러_partition의_그룹실패는_각_원본record와_연속offset으로_복구한다() throws Exception {
        fixture("partitions", 2, f -> {
            var rows = f.rows(4);
            f.failProvider();
            f.send(rows.subList(0, 2), 0);
            f.send(rows.subList(2, 4), 1);
            f.start(100, true);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(f.loop.failure).isNull();
                assertThat(f.results.count()).isEqualTo(4);
                assertThat(f.offset(0)).isEqualTo(2);
                assertThat(f.offset(1)).isEqualTo(2);
            });
            rows.forEach(row -> assertThat(f.requests.stream().flatMap(List::stream)
                    .filter(item -> item.content().equals(row.getContent())).count()).isEqualTo(2));
            assertThat(f.dlt(0)).isEqualTo(2);
            assertThat(f.dlt(1)).isEqualTo(2);
        });
    }

    private void fixture(String name, Scenario test) throws Exception {
        fixture(name, 1, test);
    }

    private void fixture(String name, int partitions, Scenario test) throws Exception {
        var mode = new ChatModerationPollBatchMeasurementTest.Mode("65-safety-" + name, true);
        ChatModerationPollBatchMeasurementTest.MODES.put(mode.name, mode);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(mode.topic, partitions, (short) 1), new NewTopic(mode.dlt, partitions, (short) 1)))
                    .all().get(10, java.util.concurrent.TimeUnit.SECONDS);
            ChatModerationPollBatchMeasurementTest.runner(mode, KAFKA.getBootstrapServers()).run(context -> {
                assertThat(context).hasNotFailed();
                mode.context = context;
                Fixture fixture = new Fixture(mode);
                try {
                    test.run(fixture);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                } finally {
                    fixture.stop();
                }
            });
        } finally {
            ChatModerationPollBatchMeasurementTest.MODES.remove(mode.name);
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(Fixture fixture) throws Exception;
    }

    static final class Fixture {
        final ChatModerationPollBatchMeasurementTest.Mode mode;
        final ChatMessageRepository messages;
        final ChatModerationRepository results;
        final ChatModerationService moderation;
        final ChatModerationBatchService service;
        final ChatModerationBatchRetryProcessor retry;
        final DefaultErrorHandler handler;
        final KafkaOperations<Object, Object> recoveryKafka;
        final List<List<AiModerationBatchPort.Item>> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger nativeCalls = new AtomicInteger();
        ModerationResult nativeResult = SAFE;
        java.util.function.Function<List<AiModerationBatchPort.Item>, List<AiModerationBatchPort.Output>> outputs =
                items -> items.stream().map(item -> new AiModerationBatchPort.Output(item.id(), SAFE)).toList();
        SizeTimeBatchOwnerLoop loop;
        Thread thread;
        java.util.function.Consumer<SizeTimeBatchOwnerLoop> configure = loop -> {};
        java.util.function.Consumer<ChatModeration> beforeSave = row -> {};

        Fixture(ChatModerationPollBatchMeasurementTest.Mode mode) {
            this.mode = mode;
            messages = mode.context.getBean(ChatMessageRepository.class);
            results = mode.context.getBean(ChatModerationRepository.class);
            var advised = (org.springframework.aop.framework.Advised) results;
            for (int i = 0; i < advised.getAdvisors().length; i++) {
                if (advised.getAdvisors()[i].getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor) {
                    advised.addAdvice(i + 1, (org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        if (invocation.getMethod().getName().equals("saveAndFlush")) {
                            beforeSave.accept((ChatModeration) invocation.getArguments()[0]);
                        }
                        return invocation.proceed();
                    });
                    break;
                }
            }
            AiModerationPort nativePort = content -> {
                nativeCalls.incrementAndGet();
                return new AiModerationResult(nativeResult, "Fake", "fake-model", null, null, null);
            };
            // 실제 Service/JPA를 유지하면서 Provider만 무과금 Test Double로 교체한다.
            moderation = spy(new ChatModerationService(messages, results, nativePort,
                    mode.context.getBean(com.bobfull.chat.application.service.ModerationRulePolicy.class),
                    mode.context.getBean(com.bobfull.chat.application.service.SplitMessageCandidateGate.class), Clock.systemUTC(),
                    mode.context.getBean(ChatModerationMetrics.class)));
            service = new ChatModerationBatchService(moderation, items -> {
                requests.add(items);
                return new AiModerationBatchPort.BatchResult(outputs.apply(items), "Fake", "fake-model");
            }, mode.context.getBean(ChatModerationBatchMetrics.class), Clock.systemUTC());
            recoveryKafka = spy(kafka());
            var recoverer = new ChatModerationDltRecoverer(recoveryKafka, moderation,
                    mode.context.getBean(com.bobfull.common.monitoring.BusinessMetricRecorder.class), mode.dlt);
            retry = new ChatModerationBatchRetryProcessor(service, recoverer, 2);
            handler = new ChatModerationBatchErrorHandler(retry, 2, 10);
            handler.addNotRetryableExceptions(com.bobfull.common.exception.CustomException.class, InvalidChatMessageEventException.class);
        }

        void failProvider() {
            outputs = items -> { throw new IllegalStateException("SHARED_FAILURE"); };
        }

        List<ChatMessage> rows(int size) {
            List<ChatMessage> rows = new ArrayList<>();
            for (int i = 0; i < size; i++) rows.add(messages.saveAndFlush(ChatMessage.create(1L, 2L, 3L, "식사 일정 확인 " + i)));
            return rows;
        }

        void send(List<ChatMessage> rows) {
            send(rows, 0);
        }

        void send(List<ChatMessage> rows, int partition) {
            for (var row : rows) {
                try {
                    kafka().send(mode.topic, partition, "synthetic", new ChatMessageCreatedEvent("synthetic", 1, row.getId(), 1L, Instant.now()))
                            .get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        }

        @SuppressWarnings("unchecked")
        KafkaOperations<Object, Object> kafka() {
            return (KafkaOperations<Object, Object>) mode.context.getBean(KafkaOperations.class);
        }

        @SuppressWarnings("unchecked")
        SizeTimeBatchOwnerLoop start(long wait, boolean awaitAssignment) {
            ConsumerFactory<Object, Object> factory = mode.context.getBean(ConsumerFactory.class);
            loop = new SizeTimeBatchOwnerLoop(() -> factory.createConsumer(mode.group, null, "-owner"), mode.topic,
                    service, retry, handler, mode.context.getBean(ChatModerationBatchMetrics.class), wait, 10);
            configure.accept(loop);
            thread = new Thread(loop, "issue65-owner");
            thread.start();
            if (awaitAssignment) await().atMost(Duration.ofSeconds(15)).until(() -> loop.assigned > 0);
            return loop;
        }

        void stop() {
            if (thread == null) return;
            loop.stop = true;
            try { thread.join(15_000); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            assertThat(thread.isAlive()).isFalse();
            assertThat(loop.failure).isNull();
            assertThat(loop.threadViolations).isZero();
            thread = null;
        }

        long offset() {
            return offset(0);
        }

        long offset(int partition) {
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                var offsets = admin.listConsumerGroupOffsets(mode.group).partitionsToOffsetAndMetadata().get();
                var key = new TopicPartition(mode.topic, partition);
                return offsets.containsKey(key) ? offsets.get(key).offset() : 0;
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }

        long dlt() {
            return dlt(0);
        }

        long dlt(int partition) {
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                return admin.listOffsets(Map.of(new TopicPartition(mode.dlt, partition), org.apache.kafka.clients.admin.OffsetSpec.latest()))
                        .all().get().values().iterator().next().offset();
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }

        void complete(int count) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(loop.failure).isNull();
                assertThat(results.count()).isEqualTo(count);
                assertThat(offset()).isEqualTo(count);
            });
        }
    }
}
