package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.infrastructure.ai.FakeAiModerationAdapter;
import com.bobfull.chat.presentation.response.ChatMessageSentResponse;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.application.service.ChatMessageCommandService;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.chat.infrastructure.outbox.ChatMessageOutboxProcessor;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * #192 Kafka/Testcontainers Evidence: "Kafka vs Async Baseline" 비교의 Kafka 축
 * (send()→Outbox→Kafka→Consumer 실측)과 Consumer 확장·복구를 측정한다.
 * Async 축은 {@code ChatMessageAsyncModerationBaselineEvidenceTest}에서 같은 Fake AI 지연·메시지 수로 측정한다.
 */
@Testcontainers
@Tag("kafka-evidence")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-moderation-consumer-concurrency-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=chat-moderation-consumer-concurrency-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-moderation-consumer-concurrency-test-api-secret",
        "portone.store-id=chat-moderation-consumer-concurrency-test-store-id",
        "portone.webhook-secret=Y2hhdC1tb2RlcmF0aW9uLWNvbmN1cnJlbmN5LXRlc3Q=",
        "outbox.chat-message.enabled=true",
        "outbox.chat-message.fixed-delay=1000",
        "outbox.chat-message.batch-size=200",
        "bobfull.kafka.chat-message.consumer-enabled=true",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
        "bobfull.kafka.chat-message.topic=chat-moderation-concurrency-it.v1",
        "bobfull.kafka.chat-message.dlt-topic=chat-moderation-concurrency-it.dlt.v1",
        "bobfull.kafka.chat-message.consumer-concurrency=3",
        "bobfull.kafka.chat-message.partition-key-strategy=chat-room",
        "bobfull.ai.moderation.fake-enabled=true",
        "bobfull.ai.moderation.fake-latency-ms=500"
})
@ContextConfiguration(classes = ChatModerationConsumerConcurrencyIntegrationTest.Configuration.class)
class ChatModerationConsumerConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ChatModerationConsumerConcurrencyIntegrationTest.class);
    private static final int SAMPLE_SIZE = 30;
    private static final long FAKE_AI_LATENCY_MILLIS = 500;
    private static final int CONCURRENCY = 3;
    private static final String TOPIC = "chat-moderation-concurrency-it.v1";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private ChatMessageCommandService service;
    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired
    private ChatModerationRepository chatModerationRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private FakeAiModerationAdapter fakeAiModerationAdapter;
    @Autowired
    private ChatMessageOutboxProcessor outboxProcessor;

    @Test
    void Kafka_경로는_같은_AI_지연에서도_send는_빠르고_Consumer_동시성만큼_완료된다() {
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(1L));
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        long baselineCompleted = chatModerationRepository.count();

        List<Long> sendElapsedMillis = new ArrayList<>(SAMPLE_SIZE);
        Instant startedAt = Instant.now();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long start = System.nanoTime();
            service.send(room.getId(), member, "kafka arm 측정용 메시지 " + i);
            sendElapsedMillis.add((System.nanoTime() - start) / 1_000_000);
        }

        List<Long> sorted = sendElapsedMillis.stream().sorted().toList();
        long p50 = sorted.get((int) (SAMPLE_SIZE * 0.50));
        long p95 = sorted.get((int) (SAMPLE_SIZE * 0.95) == SAMPLE_SIZE ? SAMPLE_SIZE - 1 : (int) (SAMPLE_SIZE * 0.95));
        long max = sorted.get(SAMPLE_SIZE - 1);

        long expectedTotal = baselineCompleted + SAMPLE_SIZE;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));
        long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();

        log.info("event=KAFKA_ARM_EVIDENCE sampleSize={} fakeAiLatencyMillis={} consumerConcurrency={} "
                        + "sendP50Millis={} sendP95Millis={} sendMaxMillis={} drainMillis={}",
                SAMPLE_SIZE, FAKE_AI_LATENCY_MILLIS, CONCURRENCY, p50, p95, max, drainMillis);

        // Kafka 경로도 send()는 Outbox 저장만 동기로 하고 Kafka 발행·AI 호출은 커밋 후 비동기라 빠르게 반환된다.
        assertThat(p95).isLessThan(500);
    }

    @Test
    void 같은_채팅방에_몰리면_Partition_key가_같아_Consumer_3개여도_한_Consumer만_처리한다() {
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(2L));
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        long baselineCompleted = chatModerationRepository.count();

        Instant startedAt = Instant.now();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            service.send(room.getId(), member, "단일 채팅방 몰림 측정용 메시지 " + i);
        }

        long expectedTotal = baselineCompleted + SAMPLE_SIZE;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));
        long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();

        log.info("event=KAFKA_ARM_SINGLE_ROOM_KEY_EVIDENCE sampleSize={} fakeAiLatencyMillis={} consumerConcurrency={} drainMillis={}",
                SAMPLE_SIZE, FAKE_AI_LATENCY_MILLIS, CONCURRENCY, drainMillis);

        // chatRoomId가 Partition key이므로 같은 방 메시지는 항상 같은 Partition에 몰려 Consumer 1개만 처리한다.
        // 이론적 순차 처리 시간(지연×N)에 근접해야 한다(오차 허용을 위해 90%로 완화).
        assertThat(drainMillis).isGreaterThanOrEqualTo((long) (FAKE_AI_LATENCY_MILLIS * SAMPLE_SIZE * 0.9));
    }

    @Test
    void 채팅방을_분산하면_Partition도_분산돼_Consumer_동시성만큼_처리량이_늘어난다() {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        List<ChatRoom> rooms = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            rooms.add(chatRoomRepository.saveAndFlush(ChatRoom.create(100L + i)));
        }
        long baselineCompleted = chatModerationRepository.count();

        Instant startedAt = Instant.now();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            ChatRoom room = rooms.get(i % CONCURRENCY);
            service.send(room.getId(), member, "채팅방 분산 측정용 메시지 " + i);
        }

        long expectedTotal = baselineCompleted + SAMPLE_SIZE;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));
        long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();

        log.info("event=KAFKA_ARM_MULTI_ROOM_KEY_EVIDENCE sampleSize={} fakeAiLatencyMillis={} consumerConcurrency={} roomCount={} drainMillis={}",
                SAMPLE_SIZE, FAKE_AI_LATENCY_MILLIS, CONCURRENCY, CONCURRENCY, drainMillis);

        // Partition key(chatRoomId)를 몇 개의 방으로 나누면 병렬 처리가 일어나 순차 처리 시간보다는
        // 확실히 빨라진다. 다만 room 3개의 key 해시가 Partition 3개에 반드시 고르게 흩어지는 것은
        // 아니라서(해시 충돌 가능) 이론적 3배 단축을 강제하지 않고 여유 있게 85% 미만으로만 검증한다.
        assertThat(drainMillis).isLessThan((long) (FAKE_AI_LATENCY_MILLIS * SAMPLE_SIZE * 0.85));
    }

    @Test
    void Consumer_1에서_2로_3으로_순차_확장하면_같은_조건에서_drain_time과_처리량이_어떻게_변하는지_실측한다() throws InterruptedException {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        List<ChatRoom> rooms = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            rooms.add(chatRoomRepository.saveAndFlush(ChatRoom.create(200L + i)));
        }
        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainers().iterator().next();

        for (int concurrency = 1; concurrency <= CONCURRENCY; concurrency++) {
            container.stop();
            container.setConcurrency(concurrency);
            container.start();
            await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
            // 재시작 직후에는 Consumer Group 재조인·Partition 재분배가 끝나지 않았을 수 있어 안정화 대기.
            Thread.sleep(2000);

            long baselineCompleted = chatModerationRepository.count();
            Instant startedAt = Instant.now();
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                ChatRoom room = rooms.get(i % CONCURRENCY);
                service.send(room.getId(), member, "concurrency=" + concurrency + " 확장 측정용 메시지 " + i);
            }

            long expectedTotal = baselineCompleted + SAMPLE_SIZE;
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));
            long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();
            double consumeRatePerSecond = SAMPLE_SIZE / (drainMillis / 1000.0);

            log.info("event=CONSUMER_SCALE_OUT_EVIDENCE concurrency={} sampleSize={} fakeAiLatencyMillis={} "
                            + "roomCount={} drainMillis={} consumeRatePerSecond={}",
                    concurrency, SAMPLE_SIZE, FAKE_AI_LATENCY_MILLIS, CONCURRENCY, drainMillis,
                    String.format("%.2f", consumeRatePerSecond));
        }

        // 다음 테스트에 영향을 주지 않도록 기본 concurrency로 되돌린다.
        container.stop();
        container.setConcurrency(CONCURRENCY);
        container.start();
        await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
    }

    @Test
    void Consumer_중단_중_적체된_이벤트는_재개후_전부_유실없이_복구된다() throws InterruptedException {
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(300L));
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        int producedCount = 15;

        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainers().iterator().next();
        if (!container.isRunning()) {
            container.start();
            await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
            Thread.sleep(2000);
        }

        container.stop();
        await().atMost(Duration.ofSeconds(10)).until(() -> !container.isRunning());

        long baselineCompleted = chatModerationRepository.count();
        for (int i = 0; i < producedCount; i++) {
            service.send(room.getId(), member, "consumer 중단 중 적체 측정용 메시지 " + i);
        }
        // Consumer가 멈춰 있으니 잠시 기다려도 처리되면 안 된다(적체 확인 — peak backlog = producedCount).
        Thread.sleep(1500);
        long duringOutageCompleted = chatModerationRepository.count();
        long peakBacklog = producedCount - (duringOutageCompleted - baselineCompleted);

        Instant recoveryStartedAt = Instant.now();
        container.start();
        await().atMost(Duration.ofSeconds(10)).until(container::isRunning);

        long expectedTotal = baselineCompleted + producedCount;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));
        long recoveryMillis = Duration.between(recoveryStartedAt, Instant.now()).toMillis();
        long lostEvents = expectedTotal - chatModerationRepository.count();

        log.info("event=CONSUMER_OUTAGE_RECOVERY_EVIDENCE produced={} peakBacklog={} processedAfterRecovery={} "
                        + "lostEvents={} recoveryMillis={}",
                producedCount, peakBacklog, producedCount - lostEvents, lostEvents, recoveryMillis);

        // Consumer 중단 중에는 반드시 0건 처리(=전부 적체)여야 한다.
        assertThat(duringOutageCompleted).isEqualTo(baselineCompleted);
        // 재개 후에는 유실 없이 전부 처리돼야 한다.
        assertThat(lostEvents).isZero();
    }

    @Test
    void AI_지연이_100ms_1s_3s로_늘어도_통합_구조에서_동시_send는_느려지지_않는다() throws InterruptedException {
        // 다른 테스트가 남긴 처리 중 작업이 있으면 먼저 다 빠지도록 기다린다(측정 오염 방지).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(chatMessageRepository.count()));

        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        int concurrentUsers = 10;
        int messagesPerUser = 2;
        long[] latenciesToTest = {100L, 1000L, 3000L};

        try {
            for (long latencyMillis : latenciesToTest) {
                ReflectionTestUtils.setField(fakeAiModerationAdapter, "latencyMs", latencyMillis);
                // 동시 사용자를 서로 다른 채팅방(Partition key 분산)에 배치해, 이 실험의 목적(AI 지연이
                // Web send()에 전파되는가)과 무관한 "같은 방 몰림"으로 인한 처리 지연이 대기시간을 왜곡하지 않게 한다.
                List<ChatRoom> rooms = new ArrayList<>();
                for (int r = 0; r < CONCURRENCY; r++) {
                    rooms.add(chatRoomRepository.saveAndFlush(ChatRoom.create(400_000L + latencyMillis * 10 + r)));
                }
                long baselineCompleted = chatModerationRepository.count();

                List<Long> sendElapsedMillis = new CopyOnWriteArrayList<>();
                ExecutorService callers = Executors.newFixedThreadPool(concurrentUsers);
                CountDownLatch ready = new CountDownLatch(concurrentUsers);
                CountDownLatch start = new CountDownLatch(1);
                for (int u = 0; u < concurrentUsers; u++) {
                    int userIndex = u;
                    ChatRoom room = rooms.get(userIndex % rooms.size());
                    callers.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int m = 0; m < messagesPerUser; m++) {
                            long callStart = System.nanoTime();
                            service.send(room.getId(), member, "동시 유입 측정 user=" + userIndex + " msg=" + m);
                            sendElapsedMillis.add((System.nanoTime() - callStart) / 1_000_000);
                        }
                    });
                }
                ready.await(5, TimeUnit.SECONDS);
                start.countDown();
                callers.shutdown();
                assertThat(callers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

                int totalMessages = concurrentUsers * messagesPerUser;
                List<Long> sorted = sendElapsedMillis.stream().sorted().toList();
                long p50 = sorted.get((int) (totalMessages * 0.50));
                int p95Index = (int) (totalMessages * 0.95) == totalMessages ? totalMessages - 1 : (int) (totalMessages * 0.95);
                long p95 = sorted.get(p95Index);
                long max = sorted.get(totalMessages - 1);

                long expectedTotal = baselineCompleted + totalMessages;
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                        assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));

                log.info("event=CHAT_SEND_UNDER_AI_LATENCY_EVIDENCE fakeAiLatencyMillis={} concurrentUsers={} "
                                + "totalMessages={} sendP50Millis={} sendP95Millis={} sendMaxMillis={}",
                        latencyMillis, concurrentUsers, totalMessages, p50, p95, max);

                // 통합 구조라도 signal 디스패치와 Kafka Consumer가 요청 스레드와 분리돼 있어,
                // AI 처리 지연이 커져도 동시 send() 응답은 계속 빨라야 한다(자원 경쟁 전파 없음).
                assertThat(p95).isLessThan(300);
            }
        } finally {
            ReflectionTestUtils.setField(fakeAiModerationAdapter, "latencyMs", FAKE_AI_LATENCY_MILLIS);
        }
    }

    @Test
    void 일부_메시지_강제_실패는_정상_메시지_처리에_영향을_주지_않고_재시도_소진후_DLT로_격리된다() {
        ChatRoom normalRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(500L));
        ChatRoom failingRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(501L));
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        int normalCount = 15;
        int failingCount = 5;

        List<Long> normalMessageIds = new ArrayList<>();
        for (int i = 0; i < normalCount; i++) {
            ChatMessageSentResponse response = service.send(normalRoom.getId(), member, "정상 메시지 " + i);
            normalMessageIds.add(response.messageId());
        }
        List<Long> failingMessageIds = new ArrayList<>();
        for (int i = 0; i < failingCount; i++) {
            ChatMessageSentResponse response = service.send(failingRoom.getId(), member,
                    FakeAiModerationAdapter.FORCE_FAIL_MARKER + " 강제 실패 메시지 " + i);
            failingMessageIds.add(response.messageId());
        }

        // 정상 메시지는 실패 이벤트와 무관하게 전부 SAFE로 성공해야 한다(실패 격리 확인).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (Long id : normalMessageIds) {
                assertThat(chatModerationRepository.findByMessageId(id).map(ChatModeration::getStatus))
                        .contains(ModerationProcessingStatus.SAFE);
            }
        });

        // 강제 실패 메시지는 재시도(consumer-max-attempts=3) 소진 후 DLT로 이동해 ANALYSIS_FAILED로 기록된다.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (Long id : failingMessageIds) {
                assertThat(chatModerationRepository.findByMessageId(id).map(ChatModeration::getStatus))
                        .contains(ModerationProcessingStatus.ANALYSIS_FAILED);
            }
        });

        long normalSuccessCount = normalMessageIds.stream()
                .filter(id -> chatModerationRepository.findByMessageId(id)
                        .map(m -> m.getStatus() == ModerationProcessingStatus.SAFE).orElse(false))
                .count();
        long failingDltCount = failingMessageIds.stream()
                .filter(id -> chatModerationRepository.findByMessageId(id)
                        .map(m -> m.getStatus() == ModerationProcessingStatus.ANALYSIS_FAILED).orElse(false))
                .count();

        log.info("event=FAILURE_ISOLATION_EVIDENCE normalCount={} normalSuccessCount={} "
                        + "failingCount={} failingDltCount={}",
                normalCount, normalSuccessCount, failingCount, failingDltCount);

        assertThat(normalSuccessCount).isEqualTo(normalCount);
        assertThat(failingDltCount).isEqualTo(failingCount);
    }

    @Test
    void 같은_채팅방_30건에서_messageId_key는_여러_Partition에_분산되고_결과를_각_messageId에_저장한다() throws InterruptedException {
        // given
        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainers().iterator().next();
        if (container.getConcurrency() != CONCURRENCY || !container.isRunning()) {
            if (container.isRunning()) {
                container.stop();
            }
            container.setConcurrency(CONCURRENCY);
            container.start();
            await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
            Thread.sleep(2000);
        }

        try {
            // when
            KeyExperimentEvidence chatRoomKey = runSameRoomKeyExperiment("chat-room", 600L);
            KeyExperimentEvidence messageIdKey = runSameRoomKeyExperiment("message-id", 601L);

            // then
            assertThat(chatRoomKey.messagesByPartition().values()).containsExactlyInAnyOrder(0L, 0L, 30L);
            assertThat(messageIdKey.messagesByPartition().values()).allMatch(count -> count > 0L);
            assertThat(messageIdKey.activePartitionCount()).isGreaterThan(1);
            assertThat(messageIdKey.drainMillis()).isLessThan(chatRoomKey.drainMillis());
        } finally {
            // 이 클래스의 기존 #192 baseline은 chat-room key를 전제로 하므로 테스트 격리를 위해 복구한다.
            ReflectionTestUtils.setField(outboxProcessor, "partitionKeyStrategy", "chat-room");
        }
    }

    private KeyExperimentEvidence runSameRoomKeyExperiment(String partitionKeyStrategy, Long reservationId) {
        ReflectionTestUtils.setField(outboxProcessor, "partitionKeyStrategy", partitionKeyStrategy);
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(reservationId));
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Map<Integer, Long> partitionOffsetsBefore = readEndOffsetsByPartition();
        long baselineCompleted = chatModerationRepository.count();
        List<Long> messageIds = new ArrayList<>(SAMPLE_SIZE);

        Instant startedAt = Instant.now();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            messageIds.add(service.send(room.getId(), member,
                    partitionKeyStrategy + " 동일 방 Partition key 비교 메시지 " + i).messageId());
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(chatModerationRepository.count()).isEqualTo(baselineCompleted + SAMPLE_SIZE);
            for (Long messageId : messageIds) {
                assertThat(chatModerationRepository.findByMessageId(messageId).map(ChatModeration::getStatus))
                        .contains(ModerationProcessingStatus.SAFE);
            }
        });
        long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();
        double messagesPerSecond = SAMPLE_SIZE / (drainMillis / 1000.0);

        Map<Integer, Long> partitionOffsetsAfter = readEndOffsetsByPartition();
        Map<Integer, Long> messagesByPartition = new TreeMap<>();
        partitionOffsetsAfter.forEach((partition, afterOffset) ->
                messagesByPartition.put(partition, afterOffset - partitionOffsetsBefore.getOrDefault(partition, 0L)));
        long activePartitionCount = messagesByPartition.values().stream().filter(count -> count > 0L).count();

        log.info("event=PARTITION_KEY_258_EVIDENCE partitionKeyStrategy={} sampleSize={} "
                        + "fakeAiLatencyMillis={} drainMillis={} messagesPerSecond={} activePartitionCount={} "
                        + "messagesByPartition={}",
                partitionKeyStrategy, SAMPLE_SIZE, FAKE_AI_LATENCY_MILLIS, drainMillis,
                String.format("%.2f", messagesPerSecond), activePartitionCount, messagesByPartition);
        return new KeyExperimentEvidence(drainMillis, messagesByPartition, activePartitionCount);
    }

    private record KeyExperimentEvidence(long drainMillis, Map<Integer, Long> messagesByPartition,
                                         long activePartitionCount) {
    }

    @Test
    void 채팅방_30개_이상에_300건을_균등분산하면_Partition_3개가_모두_활용된다() throws InterruptedException {
        int roomCount = 30;
        int totalMessages = 300;
        int messagesPerRoom = totalMessages / roomCount;
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);

        // 이전 테스트가 concurrency를 바꿔놨을 수 있으니 리뷰 요청 조건(Partition 3 / Consumer 3)으로 되돌린다.
        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainers().iterator().next();
        if (container.getConcurrency() != CONCURRENCY || !container.isRunning()) {
            if (container.isRunning()) {
                container.stop();
            }
            container.setConcurrency(CONCURRENCY);
            container.start();
            await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
            Thread.sleep(2000);
        }

        List<ChatRoom> rooms = new ArrayList<>();
        for (int i = 0; i < roomCount; i++) {
            rooms.add(chatRoomRepository.saveAndFlush(ChatRoom.create(1_000L + i)));
        }

        Map<Integer, Long> partitionOffsetsBefore = readEndOffsetsByPartition();
        long baselineCompleted = chatModerationRepository.count();

        Instant startedAt = Instant.now();
        for (int i = 0; i < totalMessages; i++) {
            ChatRoom room = rooms.get(i % roomCount);
            service.send(room.getId(), member, "30방-300건 균등분산 측정용 메시지 " + i);
        }

        long expectedTotal = baselineCompleted + totalMessages;
        await().atMost(Duration.ofSeconds(300)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));
        long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();
        double messagesPerSecond = totalMessages / (drainMillis / 1000.0);

        Map<Integer, Long> partitionOffsetsAfter = readEndOffsetsByPartition();
        Map<Integer, Long> messagesByPartition = new TreeMap<>();
        partitionOffsetsAfter.forEach((partition, afterOffset) ->
                messagesByPartition.put(partition, afterOffset - partitionOffsetsBefore.getOrDefault(partition, 0L)));

        log.info("event=KAFKA_WIDE_DISTRIBUTION_EVIDENCE roomCount={} messagesPerRoom={} totalMessages={} "
                        + "fakeAiLatencyMillis={} consumerConcurrency={} drainMillis={} messagesPerSecond={} "
                        + "messagesByPartition={}",
                roomCount, messagesPerRoom, totalMessages, FAKE_AI_LATENCY_MILLIS, CONCURRENCY, drainMillis,
                String.format("%.2f", messagesPerSecond), messagesByPartition);

        // Partition 3개가 이번 유입에서 전부 최소 1건 이상 받았는지 확인한다(파티션 병렬성이 실제로 활용됨).
        assertThat(messagesByPartition).hasSize(3);
        assertThat(messagesByPartition.values()).allMatch(count -> count > 0);
        assertThat(messagesByPartition.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(totalMessages);
    }

    @Test
    void Partition_key를_messageId로_바꾸면_분산이_더_균등해지고_Async에_근접하는지_실측한다() throws InterruptedException {
        int roomCount = 30;
        int totalMessages = 300;
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);

        ConcurrentMessageListenerContainer<?, ?> container =
                (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainers().iterator().next();
        if (container.getConcurrency() != CONCURRENCY || !container.isRunning()) {
            if (container.isRunning()) {
                container.stop();
            }
            container.setConcurrency(CONCURRENCY);
            container.start();
            await().atMost(Duration.ofSeconds(10)).until(container::isRunning);
            Thread.sleep(2000);
        }

        List<ChatRoom> rooms = new ArrayList<>();
        for (int i = 0; i < roomCount; i++) {
            rooms.add(chatRoomRepository.saveAndFlush(ChatRoom.create(2_000L + i)));
        }

        // 실험 전용: Partition key를 chatRoomId 대신 messageId로 바꾼다(운영 기본값은 그대로 chat-room).
        ReflectionTestUtils.setField(outboxProcessor, "partitionKeyStrategy", "message-id");
        try {
            Map<Integer, Long> partitionOffsetsBefore = readEndOffsetsByPartition();
            long baselineCompleted = chatModerationRepository.count();

            Instant startedAt = Instant.now();
            for (int i = 0; i < totalMessages; i++) {
                ChatRoom room = rooms.get(i % roomCount);
                service.send(room.getId(), member, "messageId key 실험용 메시지 " + i);
            }

            long expectedTotal = baselineCompleted + totalMessages;
            await().atMost(Duration.ofSeconds(300)).untilAsserted(() ->
                    assertThat(chatModerationRepository.count()).isEqualTo(expectedTotal));
            long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();
            double messagesPerSecond = totalMessages / (drainMillis / 1000.0);

            Map<Integer, Long> partitionOffsetsAfter = readEndOffsetsByPartition();
            Map<Integer, Long> messagesByPartition = new TreeMap<>();
            partitionOffsetsAfter.forEach((partition, afterOffset) ->
                    messagesByPartition.put(partition, afterOffset - partitionOffsetsBefore.getOrDefault(partition, 0L)));

            log.info("event=KAFKA_MESSAGE_ID_KEY_EVIDENCE roomCount={} totalMessages={} fakeAiLatencyMillis={} "
                            + "consumerConcurrency={} drainMillis={} messagesPerSecond={} messagesByPartition={}",
                    roomCount, totalMessages, FAKE_AI_LATENCY_MILLIS, CONCURRENCY, drainMillis,
                    String.format("%.2f", messagesPerSecond), messagesByPartition);

            assertThat(messagesByPartition).hasSize(3);
            assertThat(messagesByPartition.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(totalMessages);
        } finally {
            // 다음 테스트가 기본 전략(chat-room)을 쓴다고 가정하므로 반드시 되돌린다.
            ReflectionTestUtils.setField(outboxProcessor, "partitionKeyStrategy", "chat-room");
        }
    }

    private Map<Integer, Long> readEndOffsetsByPartition() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "chat-moderation-concurrency-it-partition-probe");
        try (KafkaConsumer<String, String> rawConsumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = rawConsumer.partitionsFor(TOPIC);
            List<TopicPartition> partitions = partitionInfos.stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition()))
                    .toList();
            Map<TopicPartition, Long> endOffsets = rawConsumer.endOffsets(partitions);
            Map<Integer, Long> byPartition = new TreeMap<>();
            endOffsets.forEach((topicPartition, offset) -> byPartition.put(topicPartition.partition(), offset));
            return byPartition;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary
        ReservationChatAccessPort fixedActiveAccessReader() {
            return (reservationId, memberId) -> new ReservationChatAccessPort.ChatAccess(
                    100L, ParticipationStatus.RESERVED, ReservationStatus.RECRUITING);
        }
    }
}
