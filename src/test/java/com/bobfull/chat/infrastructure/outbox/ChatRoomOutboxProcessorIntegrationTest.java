package com.bobfull.chat.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.application.service.ChatRoomCreationService;
import com.bobfull.outbox.entity.OutboxEvent;
import com.bobfull.outbox.entity.OutboxEventStatus;
import com.bobfull.outbox.entity.OutboxEventType;
import com.bobfull.outbox.repository.OutboxEventRepository;
import com.bobfull.outbox.service.EmailOutboxProcessor;
import com.bobfull.outbox.service.OutboxEventTransactionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chat-room-outbox-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=chat-room-outbox-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=chat-room-outbox-test-api-secret",
        "portone.store-id=chat-room-outbox-test-store-id",
        "portone.webhook-secret=Y2hhdC1yb29tLW91dGJveC10ZXN0",
        "outbox.chat-room.enabled=false"
})
@ContextConfiguration(classes = ChatRoomOutboxProcessorIntegrationTest.Configuration.class)
class ChatRoomOutboxProcessorIntegrationTest {

    @Autowired private ChatRoomOutboxProcessor processor;
    @Autowired private OutboxEventTransactionService transactionService;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private MutableClock clock;
    @Autowired private FailureMode failureMode;
    @Autowired private EmailOutboxProcessor emailProcessor;

    @AfterEach
    void cleanUp() {
        failureMode.fail = false;
        chatRoomRepository.deleteAll();
        outboxEventRepository.deleteAll();
        clock.set(Instant.parse("2026-08-08T00:00:00Z"));
    }

    @Test
    void PENDING_이벤트를_처리하면_ChatRoom을_생성하고_COMPLETED로_기록한다() {
        // given
        OutboxEvent event = pendingEvent(100L);

        // when
        processor.process(event.getId());

        // then
        assertThat(chatRoomRepository.findByReservationId(100L)).isPresent();
        assertThat(reload(event).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
    }

    @Test
    void 이미_ChatRoom이_있어도_재처리는_멱등하게_COMPLETED로_종료한다() {
        // given
        OutboxEvent event = pendingEvent(101L);
        chatRoomRepository.saveAndFlush(ChatRoom.create(101L));

        // when
        processor.process(event.getId());

        // then
        assertThat(chatRoomRepository.count()).isEqualTo(1);
        assertThat(reload(event).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
    }

    @Test
    void 일시_실패는_backoff_후_재시도해_성공으로_복구한다() {
        // given
        OutboxEvent event = pendingEvent(102L);
        failureMode.fail = true;

        // when
        processor.process(event.getId());
        OutboxEvent afterFailure = reload(event);
        failureMode.fail = false;
        clock.set(afterFailure.getNextAttemptAt());
        processor.process(event.getId());

        // then
        assertThat(afterFailure.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterFailure.getAttemptCount()).isEqualTo(1);
        assertThat(reload(event).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
        assertThat(chatRoomRepository.findByReservationId(102L)).isPresent();
    }

    @Test
    void 최초_처리_뒤_5회_재시도는_5_10_20_40_80초_backoff를_적용하고_다음_실패에서_FAILED가_된다() {
        // given
        OutboxEvent event = pendingEvent(103L);
        failureMode.fail = true;

        // when
        long[] delays = {5, 10, 20, 40, 80};
        for (int retry = 0; retry < delays.length; retry++) {
            Instant beforeFailure = clock.instant();
            processor.process(event.getId());
            OutboxEvent retryScheduled = reload(event);
            assertThat(retryScheduled.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(retryScheduled.getAttemptCount()).isEqualTo(retry + 1);
            assertThat(retryScheduled.getNextAttemptAt()).isEqualTo(beforeFailure.plusSeconds(delays[retry]));
            clock.set(retryScheduled.getNextAttemptAt());
        }
        processor.process(event.getId());

        // then
        OutboxEvent failed = reload(event);
        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(6);
        assertThat(chatRoomRepository.findByReservationId(103L)).isEmpty();
    }

    @Test
    void FAILED_이벤트는_운영_확인_후_Pending으로_재등록할수_있다() {
        // given
        OutboxEvent event = pendingEvent(106L);
        failureMode.fail = true;
        for (int attempt = 0; attempt < 6; attempt++) {
            processor.process(event.getId());
            clock.set(reload(event).getNextAttemptAt());
        }

        // when
        transactionService.retryManually(event.getId(), clock.instant());

        // then
        OutboxEvent retried = reload(event);
        assertThat(retried.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(retried.getAttemptCount()).isZero();
        assertThat(retried.getLastErrorCode()).isNull();
    }

    @Test
    void 동시에_Claim하면_같은_이벤트는_한_Processor만_선점한다() throws Exception {
        // given
        OutboxEvent event = pendingEvent(104L);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when
        try {
            Callable<Boolean> claim = () -> transactionService.claim(event.getId(),
                    List.of(OutboxEventType.CHAT_ROOM_CREATION_REQUESTED), clock.instant()).isPresent();
            Future<Boolean> first = executor.submit(claim);
            Future<Boolean> second = executor.submit(claim);

            // then
            assertThat(first.get()).isNotEqualTo(second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stale_PROCESSING은_회수한_뒤_다시_처리한다() {
        // given
        OutboxEvent event = pendingEvent(105L);
        assertThat(transactionService.claim(event.getId(),
                List.of(OutboxEventType.CHAT_ROOM_CREATION_REQUESTED), clock.instant())).isPresent();
        clock.set(clock.instant().plusSeconds(301));

        // when
        processor.processDueEvents(10);

        // then
        assertThat(chatRoomRepository.findByReservationId(105L)).isPresent();
        assertThat(reload(event).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
    }

    @Test
    void 공통_Outbox에서_ChatRoom_Processor는_자신의_Pending과_stale_이벤트만_처리한다() {
        // given
        OutboxEvent chatRoom = pendingEvent(107L);
        OutboxEvent email = emailPendingEvent(207L);
        assertThat(transactionService.claim(email.getId(),
                List.of(OutboxEventType.EMAIL_RECRUITMENT_CONFIRMED), clock.instant())).isPresent();
        clock.set(clock.instant().plusSeconds(301));

        // when
        processor.processDueEvents(10);

        // then
        assertThat(reload(chatRoom).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
        assertThat(reload(email).getStatus()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(chatRoomRepository.findByReservationId(207L)).isEmpty();
    }

    @Test
    void Email_Processor는_공통_Outbox의_ChatRoom_이벤트를_처리하지_않는다() {
        // given
        OutboxEvent chatRoom = pendingEvent(108L);
        OutboxEvent email = emailPendingEvent(208L);

        // when
        emailProcessor.processDueEvents(10);

        // then
        assertThat(reload(chatRoom).getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(reload(email).getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
        assertThat(chatRoomRepository.findByReservationId(108L)).isEmpty();
    }

    private OutboxEvent pendingEvent(Long reservationId) {
        return outboxEventRepository.saveAndFlush(OutboxEvent.chatRoomCreationRequested(reservationId, clock.instant()));
    }

    private OutboxEvent emailPendingEvent(Long reservationId) {
        return outboxEventRepository.saveAndFlush(OutboxEvent.emailNotificationRequested(
                OutboxEventType.EMAIL_RECRUITMENT_CONFIRMED, "RESERVATION", reservationId, clock.instant()));
    }

    private OutboxEvent reload(OutboxEvent event) {
        return outboxEventRepository.findById(event.getId()).orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(Instant.parse("2026-08-08T00:00:00Z")); }
        @Bean FailureMode failureMode() { return new FailureMode(); }
        @Bean @Primary ChatRoomCreationService failureInjectingChatRoomCreationService(
                ChatRoomRepository chatRoomRepository, FailureMode failureMode
        ) {
            return new ChatRoomCreationService(chatRoomRepository) {
                @Override
                public ChatRoom createIfAbsent(Long reservationId) {
                    if (failureMode.fail) throw new IllegalStateException("강제 ChatRoom 생성 실패(테스트)");
                    return super.createIfAbsent(reservationId);
                }
            };
        }
    }

    static class FailureMode { private boolean fail; }

    static class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
