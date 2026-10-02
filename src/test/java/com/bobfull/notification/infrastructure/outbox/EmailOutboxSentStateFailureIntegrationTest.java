package com.bobfull.notification.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.outbox.entity.OutboxEvent;
import com.bobfull.common.outbox.entity.OutboxEventStatus;
import com.bobfull.common.outbox.entity.OutboxEventType;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.notification.infrastructure.repository.EmailOutboxDeliveryRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:email-outbox-sent-state-failure-it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=localhost:59999",
        "spring.kafka.listener.auto-startup=false",
        "management.health.mail.enabled=false",
        "jwt.secret=email-outbox-sent-state-failure-it-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=email-outbox-sent-state-failure-it-api-secret",
        "portone.store-id=email-outbox-sent-state-failure-it-store-id",
        "portone.webhook-secret=ZW1haWwtb3V0Ym94LXNlbnQtc3RhdGUtZmFpbHVyZS1pdA==",
        "payment.expiration.enabled=false",
        "payment.refund-reconciliation.enabled=false",
        "outbox.chat-room.enabled=false",
        "outbox.email.enabled=false",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "bobfull.kafka.chat-message.topic-auto-create-enabled=false",
        "bobfull.kafka.restaurant-insight.consumer-enabled=false",
        "bobfull.ai.moderation.fake-enabled=true",
        "bobfull.ai.restaurant-insight.enabled=false"
})
@ContextConfiguration(classes = EmailOutboxSentStateFailureIntegrationTest.Configuration.class)
class EmailOutboxSentStateFailureIntegrationTest {

    private static final Instant INITIAL_TIME = Instant.parse("2026-10-02T00:00:00Z");

    @Autowired private EmailOutboxProcessor processor;
    @Autowired private OutboxEventRepository eventRepository;
    @Autowired private EmailOutboxDeliveryRepository deliveryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private MutableClock clock;
    @MockitoBean private JavaMailSender mailSender;
    @MockitoSpyBean private EmailOutboxDeliveryTransactionService deliveryTransactionService;

    @Test
    void SMTP_성공후_SENT_기록이_한번_실패하면_재처리에서_동일_수신자에게_다시_발송된다() {
        // given
        Member recipient = memberRepository.saveAndFlush(
                Member.createMember("recipient@bobfull.com", "password-hash", "수신자", "01011112222"));
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(recipient.getId(), "테스트 식당", "서울시 테스트구", "한식", "설명", "키워드", 10_000));
        SharedTable sharedTable = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), 4));
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(
                sharedTable.getId(), INITIAL_TIME.plusSeconds(86_400), INITIAL_TIME.plusSeconds(90_000)));
        Reservation reservation = reservationRepository.saveAndFlush(
                Reservation.create(timeSlot.getId(), recipient.getId()));
        ReservationParticipant participant = participantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), recipient.getId(), 1));
        OutboxEvent event = eventRepository.saveAndFlush(OutboxEvent.emailNotificationRequested(
                OutboxEventType.EMAIL_RECRUITMENT_CONFIRMED,
                "RESERVATION",
                reservation.getId(),
                clock.instant()));
        EmailOutboxDelivery delivery = deliveryRepository.saveAndFlush(
                EmailOutboxDelivery.pending(
                        event.getId(), reservation.getId(), participant.getId(), recipient.getId()));
        given(mailSender.createMimeMessage()).willAnswer(invocation -> new MimeMessage((Session) null));
        doThrow(new DataAccessResourceFailureException("강제 SENT 기록 실패(테스트)"))
                .doCallRealMethod()
                .when(deliveryTransactionService).markSent(eq(delivery.getId()), any());

        // when: 외부 발송은 성공하지만 첫 SENT 기록은 실패한다.
        processor.process(event.getId());

        // then: 수신자는 PENDING, Outbox는 재시도 가능한 PENDING으로 돌아간다.
        EmailOutboxDelivery afterFirstDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        OutboxEvent afterFirstEvent = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterFirstDelivery.getStatus()).isEqualTo(EmailDeliveryStatus.PENDING);
        assertThat(afterFirstDelivery.getSentAt()).isNull();
        assertThat(afterFirstEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterFirstEvent.getAttemptCount()).isEqualTo(1);
        verify(mailSender, times(1)).send(any(MimeMessage.class));

        // when: backoff가 지난 뒤 같은 Outbox를 다시 처리하면 SENT 기록이 성공한다.
        clock.set(INITIAL_TIME.plusSeconds(5));
        processor.process(event.getId());

        // then: 같은 수신자에게 실제 send 경계가 두 번 호출되고 최종 상태는 SENT/COMPLETED다.
        EmailOutboxDelivery finalDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();
        OutboxEvent finalEvent = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(finalDelivery.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(finalDelivery.getSentAt()).isEqualTo(clock.instant());
        assertThat(finalEvent.getStatus()).isEqualTo(OutboxEventStatus.COMPLETED);
        assertThat(finalEvent.getAttemptCount()).isEqualTo(1);
        assertThat(finalEvent.getProcessedAt()).isEqualTo(clock.instant());
        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(INITIAL_TIME);
        }
    }

    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
