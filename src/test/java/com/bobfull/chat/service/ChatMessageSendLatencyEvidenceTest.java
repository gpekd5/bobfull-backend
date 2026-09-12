package com.bobfull.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bobfull.chat.entity.ChatRoom;
import com.bobfull.chat.repository.ChatMessageRepository;
import com.bobfull.chat.repository.ChatModerationRepository;
import com.bobfull.chat.repository.ChatRoomRepository;
import com.bobfull.chat.port.ReservationChatAccessReader;
import com.bobfull.common.security.AuthMember;
import com.bobfull.common.security.MemberRole;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #59 Evidence — MAJOR 1 수정(signal 비동기화) 이후 Chat SEND 경로가 Kafka 상태와
 * 무관하게 빠르게 반환되는지 실측한다. 로컬 broker 없이 실행되므로(Kafka 미도달),
 * signal 디스패치가 여전히 send() 호출자를 막지 않음을 함께 증명한다.
 * #192 "Kafka vs Async Baseline" 비교의 Async 축(ChatMessageAsyncModerationDispatcher)도
 * 같은 컨텍스트에서 함께 측정한다(전체 빌드에서 무관한 테스트를 깨뜨리는 리소스 경합을 피하려고
 * 새 SpringBootTest 컨텍스트를 만들지 않고 이 기존 컨텍스트를 재사용한다). Kafka 축은
 * {@code ChatModerationConsumerConcurrencyIntegrationTest}에서 같은 Fake AI 지연·메시지 수로 측정한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-send-latency-evidence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=chat-send-latency-evidence-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-send-latency-evidence-api-secret",
        "portone.store-id=chat-send-latency-evidence-store-id",
        "portone.webhook-secret=Y2hhdC1zZW5kLWxhdGVuY3ktZXZpZGVuY2U=",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "spring.kafka.bootstrap-servers=localhost:59999",
        "bobfull.ai.moderation.fake-enabled=true",
        "bobfull.ai.moderation.fake-latency-ms=500",
        "bobfull.chat.moderation.async-baseline-enabled=true",
        "bobfull.chat.moderation.async-baseline-concurrency=3",
        "bobfull.chat.moderation.async-baseline-queue-capacity=1000"
})
@ContextConfiguration(classes = ChatMessageSendLatencyEvidenceTest.Configuration.class)
class ChatMessageSendLatencyEvidenceTest {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageSendLatencyEvidenceTest.class);
    private static final int SAMPLE_SIZE = 50;
    private static final int ASYNC_BASELINE_SAMPLE_SIZE = 30;
    private static final long FAKE_AI_LATENCY_MILLIS = 500;
    private static final int ASYNC_BASELINE_CONCURRENCY = 3;

    @Autowired private ChatMessageCommandService service;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatModerationRepository chatModerationRepository;

    @Test void Kafka에_도달하지_못하는_상태에서도_send는_빠르게_반환된다() {
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(1L));
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);

        List<Long> elapsedMillis = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long startedAt = System.nanoTime();
            service.send(room.getId(), member, "지연시간 측정용 메시지 " + i);
            elapsedMillis.add((System.nanoTime() - startedAt) / 1_000_000);
        }

        List<Long> sorted = elapsedMillis.stream().sorted().toList();
        long p50 = sorted.get((int) (SAMPLE_SIZE * 0.50));
        long p95 = sorted.get((int) (SAMPLE_SIZE * 0.95) == SAMPLE_SIZE ? SAMPLE_SIZE - 1 : (int) (SAMPLE_SIZE * 0.95));
        long max = sorted.get(SAMPLE_SIZE - 1);
        log.info("event=CHAT_SEND_LATENCY_EVIDENCE sampleSize={} p50Millis={} p95Millis={} maxMillis={}",
                SAMPLE_SIZE, p50, p95, max);

        // Kafka(localhost:59999)에 실제로 도달할 수 없는 상태에서도 send()는 로컬 DB 작업 수준으로 빠르게 반환돼야 한다.
        assertThat(p95).isLessThan(500);
    }

    @Test void Async_Baseline은_AI_지연과_무관하게_send는_빠르지만_완료는_동시성만큼만_처리된다() {
        // 같은 컨텍스트를 쓰는 다른 테스트가 남긴 처리 중 작업이 있으면 먼저 다 빠지도록 기다린다(측정 오염 방지).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(chatMessageRepository.count()));

        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(2L));
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        long baselineCompleted = chatModerationRepository.count();

        List<Long> sendElapsedMillis = new ArrayList<>(ASYNC_BASELINE_SAMPLE_SIZE);
        Instant startedAt = Instant.now();
        for (int i = 0; i < ASYNC_BASELINE_SAMPLE_SIZE; i++) {
            long start = System.nanoTime();
            service.send(room.getId(), member, "async baseline 측정용 메시지 " + i);
            sendElapsedMillis.add((System.nanoTime() - start) / 1_000_000);
        }

        List<Long> sorted = sendElapsedMillis.stream().sorted().toList();
        long p50 = sorted.get((int) (ASYNC_BASELINE_SAMPLE_SIZE * 0.50));
        long p95 = sorted.get((int) (ASYNC_BASELINE_SAMPLE_SIZE * 0.95) == ASYNC_BASELINE_SAMPLE_SIZE
                ? ASYNC_BASELINE_SAMPLE_SIZE - 1 : (int) (ASYNC_BASELINE_SAMPLE_SIZE * 0.95));
        long max = sorted.get(ASYNC_BASELINE_SAMPLE_SIZE - 1);

        long expectedTotal = baselineCompleted + ASYNC_BASELINE_SAMPLE_SIZE;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isGreaterThanOrEqualTo(expectedTotal));
        long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();

        log.info("event=ASYNC_BASELINE_EVIDENCE sampleSize={} fakeAiLatencyMillis={} concurrency={} "
                        + "sendP50Millis={} sendP95Millis={} sendMaxMillis={} drainMillis={}",
                ASYNC_BASELINE_SAMPLE_SIZE, FAKE_AI_LATENCY_MILLIS, ASYNC_BASELINE_CONCURRENCY, p50, p95, max, drainMillis);

        // Async Baseline은 send()를 막지 않는다(AI 처리는 별도 스레드풀).
        assertThat(p95).isLessThan(200);
        // 완료 처리량은 전용 스레드풀 동시성(3)만큼만 나가므로, 지연×(N/동시성) 근처에서 끝난다.
        assertThat(drainMillis).isGreaterThanOrEqualTo(
                FAKE_AI_LATENCY_MILLIS * (ASYNC_BASELINE_SAMPLE_SIZE / ASYNC_BASELINE_CONCURRENCY));
    }

    @Test void Async_Baseline_채팅방_30개에_300건을_균등분산해도_동시성_3만큼만_처리된다() {
        // 같은 컨텍스트를 쓰는 다른 테스트가 남긴 처리 중 작업이 있으면 먼저 다 빠지도록 기다린다(측정 오염 방지).
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isEqualTo(chatMessageRepository.count()));

        int roomCount = 30;
        int totalMessages = 300;
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        List<ChatRoom> rooms = new ArrayList<>();
        for (int i = 0; i < roomCount; i++) {
            rooms.add(chatRoomRepository.saveAndFlush(ChatRoom.create(10_000L + i)));
        }
        long baselineCompleted = chatModerationRepository.count();

        Instant startedAt = Instant.now();
        for (int i = 0; i < totalMessages; i++) {
            ChatRoom room = rooms.get(i % roomCount);
            service.send(room.getId(), member, "30방-300건 Async 균등분산 측정용 메시지 " + i);
        }

        long expectedTotal = baselineCompleted + totalMessages;
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() ->
                assertThat(chatModerationRepository.count()).isGreaterThanOrEqualTo(expectedTotal));
        long drainMillis = Duration.between(startedAt, Instant.now()).toMillis();
        double messagesPerSecond = totalMessages / (drainMillis / 1000.0);

        log.info("event=ASYNC_WIDE_DISTRIBUTION_EVIDENCE roomCount={} totalMessages={} fakeAiLatencyMillis={} "
                        + "concurrency={} drainMillis={} messagesPerSecond={}",
                roomCount, totalMessages, FAKE_AI_LATENCY_MILLIS, ASYNC_BASELINE_CONCURRENCY, drainMillis,
                String.format("%.2f", messagesPerSecond));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary
        ReservationChatAccessReader fixedActiveAccessReader() {
            return (reservationId, memberId) -> new ReservationChatAccessReader.ChatAccess(
                    100L, ParticipationStatus.RESERVED, ReservationStatus.RECRUITING);
        }
    }
}
