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
import java.util.List;
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
 * Issue #270: {@code Refund.cancellationId}(unique, 생성 시점 NULL)를 채우는 첫 취소완료 웹훅
 * 여러 건이 진짜 동시에 도착할 때, {@code RefundTransactionService#findRefundForWebhook}이
 * {@code cancellationId}로 먼저 조회하며 InnoDB 갭 락에 걸려 실제 MySQL 데드락이 나는지 검증하는
 * 선택적 통합 테스트다. BOBFULL_MYSQL_CONCURRENCY_TEST=true 일 때만 실행한다(ADR 0001 컨벤션).
 *
 * <p>#259/#264 재현 테스트는 동시 완료 단계 전에 {@code markProcessingFromWebhook}으로
 * {@code cancellationId}를 미리 채워 이 경로를 우회했다(각 PR "핵심 트러블슈팅"/"제외 범위" 참고).
 * 이 테스트는 그 우회 없이, 3건의 Refund가 전부 {@code cancellationId=NULL}인 상태 그대로
 * 최초 취소완료 웹훅을 동시에 받는 실제 시나리오를 재현한다.</p>
 *
 * <p>{@code cancellationId}가 아직 어떤 행에도 없는 값이면
 * {@code findWithLockByCancellationId}(WHERE cancellation_id = ? FOR UPDATE)는 일치하는 행이 없어
 * InnoDB가 그 값이 삽입될 갭에 갭 락을 잡는다. 3개 이상의 트랜잭션이 각자 다른 값으로 같은 갭을
 * 동시에 잠그려 하면 순환 대기(circular wait)에 빠져 실제 MySQL 데드락(Error 1213)이 발생한다.</p>
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
        "jwt.secret=refund-cancellation-id-gap-lock-mysql-concurrency-test-secret-key",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-refund-cancellation-id-gap-lock-concurrency-test-api-secret",
        "portone.store-id=portone-refund-cancellation-id-gap-lock-concurrency-test-store-id",
        "portone.webhook-secret=d2hzZWNfcmVmdW5kLWNhbmNlbGxhdGlvbi1pZC1nYXAtbG9jaw=="
})
class RefundCancellationIdGapLockMySqlConcurrencyIntegrationTest {

    @Autowired private RefundTransactionService transactionService;
    @Autowired private RefundCompletionService refundCompletionService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private SharedTableRepository sharedTableRepository;
    @Autowired private RestaurantRepository restaurantRepository;

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
    void cancellation_id가_전부_NULL인_Refund_여러_건에_최초_취소완료_웹훅이_동시에_도착해도_데드락_없이_모두_완료된다() throws Exception {
        // given: 같은 그룹 예약 참여자 3명이 각자 REQUESTED 상태 Refund를 갖고 있고, 셋 다
        // cancellation_id는 아직 NULL이다(어떤 웹훅도 받은 적 없는 최초 상태).
        TimeSlot timeSlot = timeSlot(4);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(timeSlot.getId(), 1L));
        reservation.confirm();
        reservation = reservationRepository.saveAndFlush(reservation);

        List<Payment> cancellingPayments = List.of(
                cancelRequestedPayment(reservation.getId(), 11L),
                cancelRequestedPayment(reservation.getId(), 12L),
                cancelRequestedPayment(reservation.getId(), 13L));
        for (Payment payment : cancellingPayments) {
            assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getCancellationId()).isNull();
        }

        // when: 3건의 최초 취소완료 웹훅이 각자 새로 발급된 cancellationId를 들고 진짜 동시에 도착한다.
        // markProcessingFromWebhook으로 미리 채우는 우회 없이 completeFromWebhook을 곧바로 호출한다.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(cancellingPayments.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Payment payment : cancellingPayments) {
                String cancellationId = "cancel-" + UUID.randomUUID();
                futures.add(executor.submit(() -> {
                    await(start);
                    refundCompletionService.completeFromWebhook(payment.getPaymentId(), cancellationId);
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

        // then: 데드락으로 강제 롤백되는 트랜잭션 없이 3건 모두 정상 완료돼야 한다.
        for (Payment payment : cancellingPayments) {
            assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.COMPLETED);
            assertThat(participantRepository.findById(payment.getReservationParticipantId()).orElseThrow()
                    .getParticipationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        }
    }

    private TimeSlot timeSlot(int capacity) {
        Restaurant restaurant = restaurantRepository.saveAndFlush(
                Restaurant.create(1L, "cancellation_id 갭 락 동시성 테스트 식당", "제주시", "한식", "설명", "키워드", 10000));
        SharedTable table = sharedTableRepository.saveAndFlush(SharedTable.create(restaurant.getId(), capacity));
        return timeSlotRepository.saveAndFlush(TimeSlot.create(
                table.getId(), Instant.parse("2030-08-01T02:00:00Z"), Instant.parse("2030-08-01T04:00:00Z")));
    }

    /** 취소 접수(CANCEL_REQUESTED) 상태의 참여자·PAID Payment·REQUESTED Refund(cancellation_id=NULL)를 만든다. */
    private Payment cancelRequestedPayment(Long reservationId, Long memberId) {
        ReservationParticipant participant = ReservationParticipant.create(reservationId, memberId, 1);
        participant.requestCancel("test");
        participant = participantRepository.saveAndFlush(participant);

        Payment payment = Payment.createReady("refund-gap-lock-concurrency-" + UUID.randomUUID(), memberId, 1L,
                reservationId, PaymentPurpose.JOIN, 1, BigDecimal.valueOf(10000),
                Instant.parse("2030-12-01T00:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T00:00:00Z"));
        payment.attachReservationConfirmation(reservationId, participant.getId());
        payment = paymentRepository.saveAndFlush(payment);

        transactionService.createRequested(reservationId, participant.getId(), "test");
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
