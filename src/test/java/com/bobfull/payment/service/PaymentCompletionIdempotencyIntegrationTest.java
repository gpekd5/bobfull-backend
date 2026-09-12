package com.bobfull.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.payment.port.PortOnePaymentReader;
import com.bobfull.payment.port.ReservationConfirmationPort;
import com.bobfull.payment.repository.PaymentRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.reservation.application.service.ReservationConfirmationService;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-idempotency-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=payment-idempotency-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-payment-idempotency-test-api-secret",
        "portone.store-id=portone-payment-idempotency-test-store-id",
        "portone.webhook-secret=d2hzZWNfaWRlbXBvdGVuY3ktdGVzdA=="
})
@ContextConfiguration(classes = PaymentCompletionIdempotencyIntegrationTest.IdempotencyConfiguration.class)
class PaymentCompletionIdempotencyIntegrationTest {

    @Autowired private PaymentCompletionService paymentCompletionService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository reservationParticipantRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private CountingPortOnePaymentReader paymentReader;
    @Autowired private CountingReservationConfirmationPort reservationConfirmationPort;

    @AfterEach
    void cleanUp() {
        paymentReader.reset();
        reservationConfirmationPort.reset();
        paymentRepository.deleteAll();
        reservationParticipantRepository.deleteAll();
        reservationRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
    }

    @Test
    void 완료_API를_반복_호출해도_Reservation과_Participant는_한번만_생성되고_PAID_후_외부_조회도_반복하지_않는다() {
        Payment payment = readyCreatePayment();

        paymentCompletionService.complete(payment.getPaymentId(), payment.getMemberId());
        paymentCompletionService.complete(payment.getPaymentId(), payment.getMemberId());

        assertSingleCompletion(payment);
        assertThat(paymentReader.calls()).isEqualTo(1);
        assertThat(reservationConfirmationPort.calls()).isEqualTo(1);
    }

    @Test
    void 같은_웹훅을_반복_수신해도_결과는_한번만_반영되고_PAID_후_외부_조회도_반복하지_않는다() {
        Payment payment = readyCreatePayment();

        paymentCompletionService.completeFromWebhook(payment.getPaymentId());
        paymentCompletionService.completeFromWebhook(payment.getPaymentId());

        assertSingleCompletion(payment);
        assertThat(paymentReader.calls()).isEqualTo(1);
        assertThat(reservationConfirmationPort.calls()).isEqualTo(1);
    }

    @Test
    void 완료_API와_웹훅이_동시에_같은_Payment을_처리해도_Reservation과_Participant는_한번만_생성된다() throws Exception {
        Payment payment = readyCreatePayment();
        paymentReader.blockUntilBothConcurrentReadsArrive();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> api = executor.submit(() -> paymentCompletionService.complete(payment.getPaymentId(), payment.getMemberId()));
            Future<?> webhook = executor.submit(() -> paymentCompletionService.completeFromWebhook(payment.getPaymentId()));

            assertThat(paymentReader.awaitBothConcurrentReads()).isTrue();
            paymentReader.releaseConcurrentReads();
            api.get(10, TimeUnit.SECONDS);
            webhook.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertSingleCompletion(payment);
        assertThat(reservationConfirmationPort.calls()).isEqualTo(1);
    }

    private void assertSingleCompletion(Payment payment) {
        Payment completed = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservationRepository.count()).isEqualTo(1);
        assertThat(reservationParticipantRepository.count()).isEqualTo(1);
        assertThat(completed.getReservationId()).isNotNull();
        assertThat(completed.getReservationParticipantId()).isNotNull();
        Reservation reservation = reservationRepository.findById(completed.getReservationId()).orElseThrow();
        ReservationParticipant participant = reservationParticipantRepository
                .findById(completed.getReservationParticipantId()).orElseThrow();
        assertThat(participant.getReservationId()).isEqualTo(reservation.getId());
    }

    private Payment readyCreatePayment() {
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(1L, 4));
        TimeSlot timeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(table.getId(),
                Instant.parse("2026-08-01T02:00:00Z"), Instant.parse("2026-08-01T04:00:00Z")));
        return paymentRepository.saveAndFlush(Payment.createReady("payment-" + UUID.randomUUID(), 10L, timeSlot.getId(), null,
                PaymentPurpose.CREATE, 1, BigDecimal.valueOf(10000), Instant.now().plus(Duration.ofMinutes(10))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IdempotencyConfiguration {
        @Bean
        @Primary
        CountingPortOnePaymentReader countingPortOnePaymentReader() {
            return new CountingPortOnePaymentReader();
        }

        @Bean
        @Primary
        CountingReservationConfirmationPort countingReservationConfirmationPort(ReservationConfirmationService service) {
            return new CountingReservationConfirmationPort(service);
        }
    }

    static class CountingPortOnePaymentReader implements PortOnePaymentReader {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch concurrentReads;
        private volatile CountDownLatch releaseReads;

        @Override
        public PortOnePayment read(String paymentId) {
            calls.incrementAndGet();
            CountDownLatch entered = concurrentReads;
            CountDownLatch release = releaseReads;
            if (entered != null && release != null) {
                entered.countDown();
                await(release);
            }
            return new PortOnePayment(paymentId, true, BigDecimal.valueOf(10000), Payment.CURRENCY_KRW);
        }

        void blockUntilBothConcurrentReadsArrive() {
            concurrentReads = new CountDownLatch(2);
            releaseReads = new CountDownLatch(1);
        }

        boolean awaitBothConcurrentReads() throws InterruptedException {
            return concurrentReads.await(10, TimeUnit.SECONDS);
        }

        void releaseConcurrentReads() {
            releaseReads.countDown();
        }

        int calls() { return calls.get(); }

        void reset() {
            calls.set(0);
            concurrentReads = null;
            releaseReads = null;
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 결제 검증 해제 대기 시간이 초과되었습니다.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("동시 결제 검증 대기 중 인터럽트되었습니다.", exception);
            }
        }
    }

    static class CountingReservationConfirmationPort implements ReservationConfirmationPort {
        private final ReservationConfirmationService service;
        private final AtomicInteger calls = new AtomicInteger();

        CountingReservationConfirmationPort(ReservationConfirmationService service) {
            this.service = service;
        }

        @Override
        public ReservationConfirmationResult confirm(Payment payment) {
            calls.incrementAndGet();
            ReservationConfirmationService.ReservationConfirmationResult result = service.confirm(
                    payment.getPurpose(), payment.getTimeSlotId(), payment.getReservationId(),
                    payment.getMemberId(), payment.getPartySize());
            return new ReservationConfirmationResult(result.reservationId(), result.reservationParticipantId());
        }

        int calls() { return calls.get(); }
        void reset() { calls.set(0); }
    }
}
