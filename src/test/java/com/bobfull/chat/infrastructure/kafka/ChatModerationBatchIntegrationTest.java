package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockingDetails;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.BatchConsumerAwareMessageListener;
import org.springframework.kafka.core.KafkaOperations;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.KafkaException;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:moderation-batch-it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.kafka.consumer.group-id=moderation-batch-it",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.listener.poll-timeout=100ms",
        "jwt.secret=moderation-batch-it-test-secret-key-please-keep-long", "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=test-secret", "portone.store-id=test-store", "portone.webhook-secret=dGVzdA==",
        "payment.expiration.enabled=false", "payment.refund-reconciliation.enabled=false",
        "outbox.chat-room.enabled=false", "outbox.email.enabled=false", "outbox.chat-message.enabled=false",
        "bobfull.ai.moderation.fake-enabled=true", "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.batch-enabled=true", "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
        "bobfull.kafka.chat-message.topic=moderation-batch-it.v1", "bobfull.kafka.chat-message.dlt-topic=moderation-batch-it.dlt.v1",
        "bobfull.kafka.chat-message.partitions=2", "bobfull.kafka.chat-message.consumer-max-attempts=2",
        "bobfull.kafka.chat-message.consumer-retry-backoff-ms=100", "bobfull.kafka.restaurant-insight.consumer-enabled=false"
})
class ChatModerationBatchIntegrationTest {
    private static final String TOPIC = "moderation-batch-it.v1";
    private static final String DLT = "moderation-batch-it.dlt.v1";
    private static final String GROUP = "moderation-batch-it";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final ModerationResult SAFE = new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired private ChatMessageRepository messages;
    @MockitoSpyBean private ChatModerationRepository results;
    @MockitoSpyBean private ChatModerationService moderation;
    @MockitoSpyBean private ChatModerationBatchService batchService;
    @MockitoBean private AiModerationBatchPort batch;
    @MockitoBean private AiModerationPort nativePort;
    @Autowired private KafkaOperations<Object, Object> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private MeterRegistry metrics;
    @MockitoSpyBean private ChatModerationBatchConsumer listener;
    @MockitoSpyBean private ChatModerationBatchRetryProcessor retryProcessor;
    @MockitoSpyBean(name = "kafkaTemplate") private KafkaOperations<Object, Object> kafkaTemplate;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("chatModerationBatchKafkaListenerContainerFactory")
    private ConcurrentKafkaListenerContainerFactory<Object, Object> batchFactory;
    private final List<List<AiModerationBatchPort.Item>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger nativeCalls = new AtomicInteger();

    @BeforeEach
    void prepare() {
        container().stop();
        await().until(() -> !container().isRunning());
        requests.clear();
        nativeCalls.set(0);
        when(batch.analyze(any())).thenAnswer(call -> {
            List<AiModerationBatchPort.Item> input = call.getArgument(0);
            requests.add(input);
            return success(input);
        });
        when(nativePort.analyze(anyString())).thenAnswer(call -> {
            nativeCalls.incrementAndGet();
            return new AiModerationResult(SAFE, "Fake", "fake-model", 11L, 7L, 18L);
        });
    }

    @AfterEach
    void stopConsumer() {
        container().stop();
    }

    @Test
    void 열건_Burst를_한번의_Batch로_저장하고_중복_Event는_재호출하지_않는다() throws Exception {
        // given
        List<ChatMessage> input = rows(10);
        long end = send(input);
        var sizeMetric = metrics.find("bobfull.ai.moderation.batch.size").summary();
        double before = sizeMetric == null ? 0 : sizeMetric.count();
        // when
        container().start();
        completed(input, end);
        long duplicateEnd = send(input);
        committed(duplicateEnd);
        // then
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).hasSize(10);
        assertThat(nativeCalls.get()).isZero();
        input.forEach(row -> assertThat(results.findByMessageId(row.getId()).orElseThrow().getPromptTokens()).isNull());
        assertThat(metrics.get("bobfull.ai.moderation.batch.size").summary().count() - before).isEqualTo(1);
        System.out.printf("POLL_BATCH_BURST size=10 fill=1.0 logical=1 records=10 committed=%d%n", duplicateEnd);
    }

    @Test
    void 저유입_단건은_Native로_완료하며_다음_poll을_기다리지_않는다() throws Exception {
        // given
        List<ChatMessage> input = rows(1);
        long end = send(input);
        // when
        container().start();
        completed(input, end);
        // then
        assertThat(nativeCalls.get()).isEqualTo(1);
        assertThat(requests).isEmpty();
        assertThat(results.findByMessageId(input.get(0).getId()).orElseThrow().getPromptTokens()).isEqualTo(11L);
    }

    @Test
    void 부분저장_실패중_Offset을_건너뛰지_않고_재전달시_완료건은_Skip한다() throws Exception {
        // given
        List<ChatMessage> input = rows(4);
        long failingId = input.get(1).getId();
        CountDownLatch retryEntered = new CountDownLatch(1);
        CountDownLatch allowRetry = new CountDownLatch(1);
        AtomicInteger saves = new AtomicInteger();
        var repositoryDelegate = mockingDetails(results).getMockCreationSettings().getDefaultAnswer();
        var retryMetric = metrics.find("bobfull.ai.moderation.kafka.retry").counter();
        double retriesBefore = retryMetric == null ? 0 : retryMetric.count();
        doAnswer(call -> {
            ChatModeration row = call.getArgument(0);
            if (row.getMessageId().equals(failingId)) {
                int n = saves.incrementAndGet();
                if (n == 1) {
                    throw new IllegalStateException("INJECTED_DB_SAVE_FAILURE");
                }
                retryEntered.countDown();
                if (!allowRetry.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("TEST_TIMEOUT");
                }
            }
            // Spring Data interface의 abstract method가 아니라 Spy의 원본 repository proxy에 위임한다.
            return repositoryDelegate.answer(call);
        }).when(results).saveAndFlush(any());
        long end = send(input);
        // when
        container().start();
        assertThat(retryEntered.await(15, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(offset()).isLessThanOrEqualTo(end - 3);
            assertThat(results.findByMessageId(input.get(2).getId())).isPresent();
            assertThat(results.findByMessageId(input.get(3).getId())).isPresent();
        } finally {
            allowRetry.countDown();
        }
        // then
        completed(input, end);
        assertThat(requests).hasSize(1);
        assertThat(nativeCalls.get()).isEqualTo(1);
        assertThat(saves.get()).isEqualTo(2);
        assertThat(metrics.get("bobfull.ai.moderation.kafka.retry").counter().count() - retriesBefore).isEqualTo(1);
    }

    @Test
    void 부분_Validation_실패만_재처리하고_정상_형제결과는_보존한다() throws Exception {
        // given
        var input = rows(3);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            return new AiModerationBatchPort.BatchResult(List.of(
                    new AiModerationBatchPort.Output(items.get(0).id(), SAFE),
                    new AiModerationBatchPort.Output(items.get(1).id(), null),
                    new AiModerationBatchPort.Output(items.get(2).id(), SAFE)), "Fake", "fake-model");
        }).when(batch).analyze(any());
        long end = send(input);
        // when
        container().start();
        completed(input, end);
        // then
        assertThat(requests).hasSize(1);
        assertThat(nativeCalls.get()).isEqualTo(1);
    }

    @Test
    void Provider_전체실패는_소진후_DLT와_실패DB에_기록한다() throws Exception {
        // given
        var input = rows(2);
        doThrow(new IllegalStateException("INJECTED_PROVIDER_FAILURE")).when(batch).analyze(any());
        doThrow(new IllegalStateException("INJECTED_PROVIDER_FAILURE")).when(nativePort).analyze(anyString());
        long beforeDlt = dltCount();
        long end = send(input);
        // when
        container().start();
        committed(end);
        // then
        input.forEach(row -> assertThat(results.findByMessageId(row.getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED));
        assertThat(dltCount() - beforeDlt).isEqualTo(2);
    }

    @Test
    void 공통_Provider장애는_두번의_공유요청후_해당그룹만_복구한다() throws Exception {
        // given
        var input = rows(5);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            throw new IllegalStateException("INJECTED_COMMON_PROVIDER_FAILURE");
        }).when(batch).analyze(any());
        doAnswer(call -> {
            nativeCalls.incrementAndGet();
            throw new IllegalStateException("INJECTED_COMMON_PROVIDER_FAILURE");
        }).when(nativePort).analyze(anyString());
        long beforeDlt = dltCount();
        long end = send(input);
        // when
        container().start();
        committed(end);
        // then: 같은 Before fixture에 승인된 공유 요청 Retry 계약을 적용한다.
        assertThat(requests).hasSize(2);
        assertThat(nativeCalls.get()).isZero();
        long lastParticipation = requests.stream().flatMap(List::stream)
                .filter(item -> item.content().equals(input.get(4).getContent())).count() + nativeCalls.get();
        assertThat(lastParticipation).isEqualTo(2);
        input.forEach(row -> assertThat(requests.stream().flatMap(List::stream)
                .filter(item -> item.content().equals(row.getContent())).count()).isEqualTo(2));
        assertThat(dltCount() - beforeDlt).isEqualTo(5);
        input.forEach(row -> assertThat(results.findByMessageId(row.getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED));
        System.out.printf("POLL_BATCH_COMMON_FAILURE_AFTER batch_calls=2 native_calls=0 last_record_participations=2 dlt=5%n");
    }

    @Test
    void DLT_성공후_실패DB_기록실패는_Offset을_진행시키지_않는다() throws Exception {
        // given
        var input = rows(2);
        AtomicBoolean finalWriteFailed = new AtomicBoolean();
        CountDownLatch replayEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        doAnswer(call -> {
            providerCalls.incrementAndGet();
            throw new IllegalStateException("INJECTED_PROVIDER_FAILURE");
        }).when(batch).analyze(any());
        doAnswer(call -> {
            if (!finalWriteFailed.getAndSet(true)) throw new IllegalStateException("INJECTED_FINAL_DB_FAILURE");
            replayEntered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
            return call.callRealMethod();
        }).when(moderation).recordFinalFailure(eq(input.get(0).getId()), anyString());
        long beforeDlt = dltCount();
        long end = send(input);
        // when
        container().start();
        try {
            assertThat(replayEntered.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(offset()).isLessThanOrEqualTo(end - 2);
            assertThat(results.findByMessageId(input.get(0).getId())).isEmpty();
        } finally {
            release.countDown();
        }
        committed(end);
        // then
        assertThat(results.findByMessageId(input.get(0).getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED);
        assertThat(results.findByMessageId(input.get(1).getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED);
        verify(moderation, times(2)).recordFinalFailure(eq(input.get(0).getId()), anyString());
        assertThat(dltCount() - beforeDlt).isEqualTo(3);
        assertThat(providerCalls.get()).isEqualTo(2);
        var following = rows(1);
        completed(following, send(following));
        assertThat(results.findByMessageId(following.get(0).getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.SAFE);
    }

    @Test
    void Consumer_stop_start후_적체를_복구하고_지속유입도_완료한다() throws Exception {
        // given
        var first = rows(10);
        long firstEnd = send(first);
        // when
        container().start();
        completed(first, firstEnd);
        container().stop();
        var backlog = rows(10);
        long backlogEnd = send(backlog);
        container().start();
        completed(backlog, backlogEnd);
        for (int i = 0; i < 3; i++) {
            var low = rows(1);
            completed(low, send(low));
        }
        // then
        assertThat(requests).allSatisfy(items -> assertThat(items.size()).isBetween(2, 10));
        assertThat(requests.stream().mapToInt(List::size).sum() + nativeCalls.get()).isEqualTo(23);
        assertThat(metrics.find("bobfull.ai.moderation.e2e").timers()).isNotEmpty();
        verify(retryProcessor, org.mockito.Mockito.atLeastOnce()).revoke(org.mockito.ArgumentMatchers.argThat(partitions -> !partitions.isEmpty()));
        verify(retryProcessor, org.mockito.Mockito.atLeastOnce()).clearThreadState();
        metrics.find("bobfull.ai.moderation.e2e").timers().forEach(timer -> System.out.printf(
                "POLL_BATCH_E2E path=%s count=%d total_ms=%.3f max_ms=%.3f%n",
                timer.getId().getTag("path"), timer.count(), timer.totalTime(TimeUnit.MILLISECONDS),
                timer.max(TimeUnit.MILLISECONDS)));
    }

    @Test
    void 저장후_Listener실패로_재전달되어도_Provider를_다시_호출하지_않는다() throws Exception {
        // given
        var input = rows(3);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            call.callRealMethod();
            if (first.getAndSet(false)) {
                throw new BatchModerationException(0, new IllegalStateException("AFTER_DB_BEFORE_ACK"));
            }
            return null;
        }).when(batchService).analyze(any());
        long end = send(input);
        // when
        container().start();
        completed(input, end);
        // then
        assertThat(requests).hasSize(1);
        assertThat(nativeCalls.get()).isZero();
    }

    @Test
    void 동일그룹_두_Consumer가_중복_Event를_동시에_처리해도_DB는_한건이다() throws Exception {
        // given
        var input = rows(2);
        var second = batchFactory.createContainer(TOPIC);
        second.getContainerProperties().setGroupId(GROUP);
        second.getContainerProperties().setMessageListener((BatchConsumerAwareMessageListener<Object, Object>) listener::onMessages);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
            return success(items);
        }).when(batch).analyze(any());
        container().start();
        second.start();
        doAnswer(call -> {
            nativeCalls.incrementAndGet();
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
            return new AiModerationResult(SAFE, "Fake", "fake-model", 11L, 7L, 18L);
        }).when(nativePort).analyze(anyString());
        try {
            await().atMost(Duration.ofSeconds(15)).until(() -> container().getAssignedPartitions() != null
                    && container().getAssignedPartitions().size() == 1 && second.getAssignedPartitions() != null
                    && second.getAssignedPartitions().size() == 1);
            container().pause();
            second.pause();
            await().atMost(Duration.ofSeconds(10)).until(() -> container().isContainerPaused() && second.isContainerPaused());
            long end = send(input);
            long secondEnd = -1;
            for (ChatMessage row : input) {
                secondEnd = kafka.send(TOPIC, 1, "duplicate", new ChatMessageCreatedEvent(UUID.randomUUID().toString(),
                        1, row.getId(), 1L, Instant.now())).get(10, TimeUnit.SECONDS).getRecordMetadata().offset() + 1;
            }
            // when
            container().resume();
            second.resume();
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            completed(input, end);
            long expected = secondEnd;
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(offset(new TopicPartition(TOPIC, 1))).isEqualTo(expected));
            // then: 외부 호출 exactly-once가 아니라 최종 DB unique/optimistic-lock 방어를 검증한다.
            assertThat(requests.size() + nativeCalls.get()).isBetween(2, 4);
            assertThat(results.findByMessageIdIn(input.stream().map(ChatMessage::getId).toList())).hasSize(2);
            System.out.printf("POLL_BATCH_DUPLICATE_CONSUMERS batch_calls=%d native_calls=%d unique_rows=2%n",
                    requests.size(), nativeCalls.get());
        } finally {
            release.countDown();
            second.stop();
        }
    }

    @Test
    void 동시_Batch_저장충돌도_기존_Unique와_Version으로_방어한다() throws Exception {
        // given: Kafka의 작은 poll이 Native로 분리되는 경우와 별개로 실제 Batch 저장 경쟁을 검증한다.
        var input = rows(2);
        var ids = input.stream().map(ChatMessage::getId).toList();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
            return success(items);
        }).when(batch).analyze(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            // when
            var first = executor.submit(() -> batchService.analyze(ids));
            var second = executor.submit(() -> batchService.analyze(ids));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            // then
            assertThat(requests).hasSize(2);
            assertThat(nativeCalls.get()).isZero();
            assertThat(results.findByMessageIdIn(ids)).hasSize(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 실패그룹_밖의_새메시지와_Rule은_재요청하거나_DLT하지_않는다() throws Exception {
        // given
        var failing = rows(2);
        var healthy = rows(2);
        var rule = messages.saveAndFlush(ChatMessage.create(1L, 2L, 3L, "씨발"));
        var failures = failing.stream().map(ChatMessage::getContent).collect(java.util.stream.Collectors.toSet());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            if (items.stream().anyMatch(item -> failures.contains(item.content()))) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
                throw new IllegalStateException("SHARED_FAILURE");
            }
            return success(items);
        }).when(batch).analyze(any());
        long before = dltCount();
        send(failing);
        container().start();
        // when: 최초 실패 요청 중 새 메시지를 뒤에 발행한다.
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        var following = new ArrayList<>(healthy);
        following.add(rule);
        long end;
        try {
            end = send(following);
        } finally {
            release.countDown();
        }
        completed(following, end);
        // then
        assertThat(dltCount() - before).isEqualTo(2);
        failing.forEach(row -> assertThat(results.findByMessageId(row.getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED));
        healthy.forEach(row -> assertThat(requests.stream().flatMap(List::stream)
                .filter(item -> item.content().equals(row.getContent())).count()).isEqualTo(1));
        assertThat(requests).noneSatisfy(items -> assertThat(items).anyMatch(item -> item.content().equals(rule.getContent())));
    }

    @Test
    void 공유재시도_성공후_항목_Validation실패는_기존_indexed_Retry를_사용한다() throws Exception {
        // given
        var input = rows(3);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("SHARED_FAILURE");
            return new AiModerationBatchPort.BatchResult(List.of(
                    new AiModerationBatchPort.Output(items.get(0).id(), SAFE),
                    new AiModerationBatchPort.Output(items.get(1).id(), null),
                    new AiModerationBatchPort.Output(items.get(2).id(), SAFE)), "Fake", "fake-model");
        }).when(batch).analyze(any());
        long before = dltCount();
        long end = send(input);
        // when
        container().start();
        completed(input, end);
        // then
        assertThat(requests).hasSize(2);
        assertThat(nativeCalls.get()).isEqualTo(1);
        assertThat(dltCount()).isEqualTo(before);
    }

    @Test
    void Batch전체_ID매핑_실패도_두번의_공유시도만_소비한다() throws Exception {
        // given
        var input = rows(2);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            return new AiModerationBatchPort.BatchResult(List.of(
                    new AiModerationBatchPort.Output(items.get(0).id(), SAFE),
                    new AiModerationBatchPort.Output(items.get(0).id(), SAFE)), "Fake", "fake-model");
        }).when(batch).analyze(any());
        long before = dltCount();
        long end = send(input);
        // when
        container().start();
        committed(end);
        // then
        assertThat(requests).hasSize(2);
        assertThat(nativeCalls.get()).isZero();
        assertThat(dltCount() - before).isEqualTo(2);
        input.forEach(row -> {
            var result = results.findByMessageId(row.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED);
            assertThat(result.getErrorCode()).isEqualTo("MODERATION_BATCH_MAPPING");
        });
    }

    @Test
    void indexed_Validation소진후_DB복구실패도_Provider를_재호출하지_않는다() throws Exception {
        // given
        var input = rows(2);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            return new AiModerationBatchPort.BatchResult(List.of(
                    new AiModerationBatchPort.Output(items.get(0).id(), null),
                    new AiModerationBatchPort.Output(items.get(1).id(), SAFE)), "Fake", "fake-model");
        }).when(batch).analyze(any());
        doAnswer(call -> {
            nativeCalls.incrementAndGet();
            return new AiModerationResult(null, "Fake", "fake-model", null, null, null);
        }).when(nativePort).analyze(anyString());
        AtomicBoolean first = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            if (first.getAndSet(false)) throw new IllegalStateException("FINAL_DB_FAILURE");
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
            return call.callRealMethod();
        }).when(moderation).recordFinalFailure(eq(input.get(0).getId()), anyString());
        long before = dltCount();
        long end = send(input);
        // when
        container().start();
        try {
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(offset()).isLessThanOrEqualTo(end - 2);
            assertThat(requests).hasSize(1);
            assertThat(nativeCalls.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        committed(end);
        // then
        assertThat(requests).hasSize(1);
        assertThat(nativeCalls.get()).isEqualTo(1);
        assertThat(dltCount() - before).isEqualTo(2);
        verify(moderation, times(2)).recordFinalFailure(eq(input.get(0).getId()), anyString());
        assertThat(results.findByMessageId(input.get(0).getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED);
        assertThat(results.findByMessageId(input.get(1).getId()).orElseThrow().getStatus()).isEqualTo(ModerationProcessingStatus.SAFE);
    }

    @Test
    void DLT발행_실패중에는_DB와_offset을_진행하지_않고_복구만_재시도한다() throws Exception {
        // given
        var input = rows(2);
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch retryEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            providerCalls.incrementAndGet();
            throw new IllegalStateException("PROVIDER_FAILURE");
        }).when(batch).analyze(any());
        doAnswer(call -> {
            ProducerRecord<Object, Object> record = call.getArgument(0);
            if (DLT.equals(record.topic())) {
                int number = sends.incrementAndGet();
                if (number == 1) return CompletableFuture.failedFuture(new KafkaException("INJECTED_DLT_FAILURE"));
                if (number == 3) {
                    retryEntered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT");
                }
            }
            return call.callRealMethod();
        }).when(kafkaTemplate).send(org.mockito.ArgumentMatchers.<ProducerRecord<Object, Object>>any());
        long before = dltCount();
        long end = send(input);
        // when
        container().start();
        try {
            assertThat(retryEntered.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(offset()).isLessThanOrEqualTo(end - 2);
            assertThat(results.findByMessageId(input.get(0).getId())).isEmpty();
            verify(moderation, times(0)).recordFinalFailure(eq(input.get(0).getId()), anyString());
            assertThat(providerCalls.get()).isEqualTo(2);
        } finally {
            release.countDown();
        }
        committed(end);
        // then: 실패 send는 record를 만들지 않고 완전히 복구된 형제는 재발행하지 않는다.
        assertThat(sends.get()).isEqualTo(3);
        assertThat(dltCount() - before).isEqualTo(2);
        assertThat(providerCalls.get()).isEqualTo(2);
        input.forEach(row -> assertThat(results.findByMessageId(row.getId()).orElseThrow().getStatus())
                .isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED));
    }

    @Test
    void 복구완료후_ACK전_실패해도_현재할당에서는_Provider와_DLT를_반복하지_않는다() throws Exception {
        // given
        var input = rows(2);
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            throw new IllegalStateException("SHARED_FAILURE");
        }).when(batch).analyze(any());
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            call.callRealMethod();
            if (first.getAndSet(false)) {
                throw new ChatModerationBatchRetryProcessor.GroupRetryException(0, new IllegalStateException("AFTER_RECOVERY_BEFORE_ACK"));
            }
            return null;
        }).when(retryProcessor).analyze(any());
        long before = dltCount();
        long end = send(input);
        // when
        container().start();
        committed(end);
        // then
        assertThat(requests).hasSize(2);
        assertThat(nativeCalls.get()).isZero();
        assertThat(dltCount() - before).isEqualTo(2);
    }

    @Test
    void 한_poll의_여러_partition_공유실패도_각_offset과_DB복구를_보존한다() throws Exception {
        // given
        var input = rows(4);
        List<Set<Integer>> observedPolls = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            List<org.apache.kafka.clients.consumer.ConsumerRecord<Object, Object>> records = call.getArgument(0);
            observedPolls.add(records.stream().map(org.apache.kafka.clients.consumer.ConsumerRecord::partition)
                    .collect(java.util.stream.Collectors.toSet()));
            return call.callRealMethod();
        }).when(listener).onMessages(any(), any());
        doAnswer(call -> {
            List<AiModerationBatchPort.Item> items = call.getArgument(0);
            requests.add(items);
            throw new IllegalStateException("SHARED_FAILURE");
        }).when(batch).analyze(any());
        var nativeParticipants = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        doAnswer(call -> {
            nativeCalls.incrementAndGet();
            nativeParticipants.computeIfAbsent(call.getArgument(0), unused -> new AtomicInteger()).incrementAndGet();
            throw new IllegalStateException("SHARED_FAILURE");
        }).when(nativePort).analyze(anyString());
        long before = dltCount();
        long beforeSecond = endOffset(new TopicPartition(DLT, 1));
        long endFirst = send(input.subList(0, 2));
        long endSecond = -1;
        for (ChatMessage row : input.subList(2, 4)) {
            endSecond = kafka.send(TOPIC, 1, "synthetic", new ChatMessageCreatedEvent("synthetic", 1, row.getId(), 1L, Instant.now()))
                    .get(10, TimeUnit.SECONDS).getRecordMetadata().offset() + 1;
        }
        long expectedSecond = endSecond;
        // 두 position을 명시해 초기 할당/position 탐색 편차를 제거한다. 실제 Broker poll/Listener/ACK를 사용한다.
        // 이 테스트만 fetch 최소량/대기를 지정하며 성능 비교나 group 재균형 검증으로 해석하지 않는다.
        var mixed = batchFactory.createContainer(
                new org.springframework.kafka.support.TopicPartitionOffset(TOPIC, 0, endFirst - 2),
                new org.springframework.kafka.support.TopicPartitionOffset(TOPIC, 1, endSecond - 2));
        mixed.getContainerProperties().setGroupId(GROUP);
        mixed.getContainerProperties().setMessageListener((BatchConsumerAwareMessageListener<Object, Object>) listener::onMessages);
        mixed.getContainerProperties().getKafkaConsumerProperties().put("fetch.min.bytes", "4096");
        mixed.getContainerProperties().getKafkaConsumerProperties().put("fetch.max.wait.ms", "500");
        try {
            // when
            mixed.start();
            committed(endFirst);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(offset(new TopicPartition(TOPIC, 1))).isEqualTo(expectedSecond));
            // then
            assertThat(observedPolls).anyMatch(partitions -> partitions.size() == 2);
            input.forEach(row -> {
                long batchParticipation = requests.stream().flatMap(List::stream)
                        .filter(item -> item.content().equals(row.getContent())).count();
                int nativeParticipation = nativeParticipants.getOrDefault(row.getContent(), new AtomicInteger()).get();
                assertThat(batchParticipation + nativeParticipation).isEqualTo(2);
                assertThat(results.findByMessageId(row.getId()).orElseThrow().getStatus()).isEqualTo(ModerationProcessingStatus.ANALYSIS_FAILED);
            });
            assertThat(dltCount() - before).isEqualTo(2);
            assertThat(endOffset(new TopicPartition(DLT, 1)) - beforeSecond).isEqualTo(2);
        } finally {
            mixed.stop();
        }
    }

    private long endOffset(TopicPartition partition) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS).get(partition).offset();
        }
    }

    private MessageListenerContainer container() {
        return listeners.getListenerContainer("chatModerationBatch");
    }

    private List<ChatMessage> rows(int count) {
        List<ChatMessage> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(messages.saveAndFlush(ChatMessage.create(1L, 2L, 3L, "FAKE_EVALUATION_" + UUID.randomUUID())));
        }
        return rows;
    }

    private long send(List<ChatMessage> rows) throws Exception {
        long end = -1;
        for (ChatMessage row : rows) {
            end = kafka.send(TOPIC, 0, "same-partition", new ChatMessageCreatedEvent(UUID.randomUUID().toString(),
                    1, row.getId(), 1L, Instant.now())).get(10, TimeUnit.SECONDS).getRecordMetadata().offset() + 1;
        }
        return end;
    }

    private void completed(List<ChatMessage> input, long end) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> input.forEach(row ->
                assertThat(results.findByMessageId(row.getId())).isPresent().get()
                        .extracting(ChatModeration::isCompleted).isEqualTo(true)));
        committed(end);
    }

    private void committed(long end) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(offset()).isEqualTo(end));
    }

    private long offset() throws Exception {
        return offset(PARTITION);
    }

    private long offset(TopicPartition partition) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var value = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
            return value == null ? -1 : value.offset();
        }
    }

    private long dltCount() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            return admin.listOffsets(Map.of(new TopicPartition(DLT, 0), OffsetSpec.latest()))
                    .all().get(5, TimeUnit.SECONDS).get(new TopicPartition(DLT, 0)).offset();
        }
    }

    private AiModerationBatchPort.BatchResult success(List<AiModerationBatchPort.Item> items) {
        return new AiModerationBatchPort.BatchResult(items.stream()
                .map(item -> new AiModerationBatchPort.Output(item.id(), SAFE)).toList(), "Fake", "fake-model");
    }
}
