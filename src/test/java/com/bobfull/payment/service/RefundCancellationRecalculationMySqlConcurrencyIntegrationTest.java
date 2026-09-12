package com.bobfull.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.payment.entity.RefundStatus;
import com.bobfull.payment.repository.PaymentRepository;
import com.bobfull.payment.repository.RefundRepository;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Issue #264: 그룹 예약 전체 취소(CANCELLING)가 아닌 개별 참여자 취소 완료 경로
 * ({@code ReservationCancellationCompletionService#complete}의 else 분기,
 * {@code ReservationCancellationTransactionService#recalculateAfterCompletion})가 실제 MySQL에서
 * 여러 참여자의 취소 완료 웹훅을 진짜 동시에 처리할 때도 남은 인원을 정확히 재계산하는지 검증하는
 * 선택적 통합 테스트다. BOBFULL_MYSQL_CONCURRENCY_TEST=true 일 때만 실행한다(ADR 0001 컨벤션).
 *
 * <p>{@code recalculateAfterCompletion}은 잠금 없는 {@code sumPartySizeByStatuses}(SUM 집계)로
 * 남은 유효 인원을 계산한다. 이 read가 속한 트랜잭션은 그보다 먼저
 * {@code RefundTransactionService}에서 {@code refund.getPayment()} LAZY 로딩(잠금 없는 SELECT)을
 * 실행하는데, MySQL InnoDB REPEATABLE READ에서는 이 최초 SELECT 시점에 트랜잭션 스냅샷이 고정된다.
 * 여러 참여자의 취소 완료가 각자 이 스냅샷을 서로의 커밋 전에 고정시키면, Reservation 행 락을 잡은
 * 뒤에도 이미 다른 트랜잭션이 커밋한 취소 완료를 보지 못한 채 남은 인원을 과다 계산해
 * {@code revertToRecruiting()}이 호출되어야 할 상황에서도 CONFIRMED에 잘못 머무를 수 있다
 * (#259와 동일한 메커니즘이 다른 분기에 남아 있는 경우). H2로는 이 결함이 재현되지 않는다.</p>
 *
 * <p><b>주의</b>: {@code spring.jpa.hibernate.ddl-auto=create-drop}이라 실행할 때마다 대상 DB의
 * 모든 테이블을 지우고 다시 만든다. {@code BOBFULL_TEST_MYSQL_URL}은 반드시 로컬 개발용 DB가 아닌
 * 별도 스키마를 가리켜야 한다.</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_CONCURRENCY_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payment.expiration.enabled=false",
        "jwt.secret=refund-cancellation-recalculation-mysql-concurrency-test-secret-key",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-refund-cancellation-recalculation-concurrency-test-api-secret",
        "portone.store-id=portone-refund-cancellation-recalculation-concurrency-test-store-id",
        "portone.webhook-secret=d2hzZWNfcmVmdW5kLWNhbmNlbGxhdGlvbi1yZWNhbGN1bGF0aW9u"
})
class RefundCancellationRecalculationMySqlConcurrencyIntegrationTest {

    @Autowired private RefundTransactionService transactionService;
    @Autowired private RefundCompletionService refundCompletionService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;

    private final Map<String, String> cancellationIdByPaymentId = new HashMap<>();

    @AfterEach
    void cleanUp() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
        participantRepository.deleteAll();
        reservationRepository.deleteAll();
        timeSlotRepository.deleteAll();
        sharedTableRepository.deleteAll();
        restaurantRepository.deleteAll();
    }

    @Test
    void 확정된_예약에서_참여자_여럿이_동시에_개별_취소를_완료하면_기준_인원_미달로_모집중으로_되돌아간다() throws Exception {
        // given: 정원 4석, 확정 기준 3명(capacity-1)인 CONFIRMED 예약에 4명이 RESERVED로 참여 중이다.
        TimeSlot timeSlot = timeSlot(4);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
        reservation.confirm();
        reservation = reservationRepository.saveAndFlush(reservation);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        ReservationParticipant staying = participantRepository.saveAndFlush(
                ReservationParticipant.create(reservation.getId(), 10L, 1));
        List<Payment> cancellingPayments = List.of(
                cancelRequestedPayment(reservation.getId(), 11L),
                cancelRequestedPayment(reservation.getId(), 12L),
                cancelRequestedPayment(reservation.getId(), 13L));

        // when: 3명의 개별 취소 완료 웹훅이 진짜 동시에 도착한다. 실제로 남는 참여자는 staying 1명뿐이라
        // 취소가 전부 반영되면 3(threshold) 미만이 되어 예약은 RECRUITING으로 되돌아가야 한다.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(cancellingPayments.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Payment payment : cancellingPayments) {
                futures.add(executor.submit(() -> {
                    await(start);
                    refundCompletionService.completeFromWebhook(
                            payment.getPaymentId(), cancellationIdByPaymentId.get(payment.getPaymentId()));
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // then: 참여자·결제·환불 개별 상태는 전부 정확히 완료되지만, 잠금 없는 SUM 재계산이 서로의
        // 커밋을 보지 못하면 Reservation이 CONFIRMED에 잘못 머무른다.
        for (Payment payment : cancellingPayments) {
            assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.COMPLETED);
            assertThat(participantRepository.findById(payment.getReservationParticipantId()).orElseThrow()
                    .getParticipationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        }
        assertThat(participantRepository.findById(staying.getId()).orElseThrow().getParticipationStatus())
                .isEqualTo(ParticipationStatus.RESERVED);

        int actualRemainingCount = participantRepository.sumPartySize(reservation.getId(), ParticipationStatus.RESERVED);
        assertThat(actualRemainingCount).isEqualTo(1);
        assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.RECRUITING);
    }

    private TimeSlot timeSlot(int capacity) {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(1L, "재계산 동시성 테스트 식당", "제주시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), capacity));
        return timeSlotRepository.saveAndFlush(TimeSlot.create(
                table.getId(), Instant.parse("2030-08-01T02:00:00Z"), Instant.parse("2030-08-01T04:00:00Z")));
    }

    /** 취소 접수(CANCEL_REQUESTED) 상태의 참여자·PAID Payment·REQUESTED Refund를 함께 만든다. */
    private Payment cancelRequestedPayment(Long reservationId, Long memberId) {
        ReservationParticipant participant = ReservationParticipant.create(reservationId, memberId, 1);
        participant.requestCancel("test");
        participant = participantRepository.saveAndFlush(participant);

        Payment payment = Payment.createReady("refund-recalc-concurrency-" + UUID.randomUUID(), memberId, 1L,
                reservationId, PaymentPurpose.JOIN, 1, BigDecimal.valueOf(10000),
                Instant.parse("2030-12-01T00:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T00:00:00Z"));
        payment.attachReservationConfirmation(reservationId, participant.getId());
        payment = paymentRepository.saveAndFlush(payment);

        transactionService.createRequested(reservationId, participant.getId(), "test");
        // cancellationId를 미리 채워, 동시 완료 단계에서 findWithLockByCancellationId가 존재하는
        // unique 값만 정확히 매칭하는 행 락을 얻도록 한다(#259 재현 테스트와 동일한 이유의 우회).
        // cancellation_id 컬럼 길이 제한(64자) 때문에 paymentId에서 파생하지 않고 별도로 발급한다.
        String cancellationId = "cancel-" + UUID.randomUUID();
        cancellationIdByPaymentId.put(payment.getPaymentId(), cancellationId);
        refundCompletionService.markProcessingFromWebhook(payment.getPaymentId(), cancellationId);
        return payment;
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 제어 중 인터럽트되었습니다.", exception);
        }
    }
}
