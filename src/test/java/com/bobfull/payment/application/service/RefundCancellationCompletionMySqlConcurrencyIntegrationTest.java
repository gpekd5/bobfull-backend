package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Issue #259: 그룹 예약 참여자 여럿의 환불 완료 웹훅이 실제 MySQL에서 진짜 동시에 도착해도
 * Reservation이 CANCELLED로 정확히 전이되는지 검증하는 선택적 통합 테스트다.
 * BOBFULL_MYSQL_CONCURRENCY_TEST=true 일 때만 실행한다(ADR 0001 컨벤션).
 *
 * <p>H2(MODE=MySQL)로는 이 결함이 재현되지 않는다 — MySQL InnoDB의 REPEATABLE READ
 * 스냅샷 고정 동작(트랜잭션의 첫 잠금 없는 SELECT가 스냅샷 시점을 고정)이 H2와 다르기
 * 때문이다. 그래서 실제 MySQL이 반드시 필요하다.</p>
 *
 * <p><b>주의</b>: {@code spring.jpa.hibernate.ddl-auto=create-drop}이라 실행할 때마다 대상 DB의
 * 모든 테이블을 지우고 다시 만든다. {@code BOBFULL_TEST_MYSQL_URL}은 반드시 로컬 개발용 DB(예:
 * {@code bobfull})가 아닌 별도 스키마를 가리켜야 한다. 개발 DB를 가리키면 실행할 때마다 회원·식당·
 * 예약 등 실제 데이터가 전부 삭제된다(2026-08-03에 실제로 발생한 사고).</p>
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_MYSQL_CONCURRENCY_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BOBFULL_TEST_MYSQL_URL}",
        "spring.datasource.username=${BOBFULL_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${BOBFULL_TEST_MYSQL_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payment.expiration.enabled=false",
        "jwt.secret=refund-cancellation-completion-mysql-concurrency-test-secret-key",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=portone-refund-cancellation-completion-concurrency-test-api-secret",
        "portone.store-id=portone-refund-cancellation-completion-concurrency-test-store-id",
        "portone.webhook-secret=d2hzZWNfcmVmdW5kLWNhbmNlbGxhdGlvbi1jb21wbGV0aW9u"
})
class RefundCancellationCompletionMySqlConcurrencyIntegrationTest {

    @Autowired private RefundTransactionService transactionService;
    @Autowired private RefundCompletionService refundCompletionService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository participantRepository;

    @AfterEach
    void cleanUp() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
        participantRepository.deleteAll();
        reservationRepository.deleteAll();
    }

    @Test
    void 참여자_3명의_환불완료_웹훅이_동시에_도착해도_Reservation은_CANCELLED로_확정된다() throws Exception {
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.create(1L, 1L));
        reservation.startCancelling();
        reservation = reservationRepository.saveAndFlush(reservation);

        List<Payment> payments = List.of(
                cancelRequestedPayment(reservation.getId(), 1L),
                cancelRequestedPayment(reservation.getId(), 2L),
                cancelRequestedPayment(reservation.getId(), 3L));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(payments.size());
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (Payment payment : payments) {
                futures.add(executor.submit(() -> {
                    await(start);
                    refundCompletionService.completeFromWebhook(
                            payment.getPaymentId(), "cancel-" + payment.getPaymentId());
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

        for (Payment payment : payments) {
            assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.COMPLETED);
            assertThat(participantRepository.findById(payment.getReservationParticipantId()).orElseThrow()
                    .getParticipationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        }
        assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    /** 취소 접수(CANCEL_REQUESTED) 상태의 참여자·PAID Payment·REQUESTED Refund를 함께 만든다. */
    private Payment cancelRequestedPayment(Long reservationId, Long memberId) {
        ReservationParticipant participant = ReservationParticipant.create(reservationId, memberId, 1);
        participant.requestCancel("test");
        participant = participantRepository.saveAndFlush(participant);

        Payment payment = Payment.createReady("refund-concurrency-" + UUID.randomUUID(), memberId, 1L,
                reservationId, PaymentPurpose.JOIN, 1, BigDecimal.valueOf(10000),
                Instant.parse("2030-12-01T00:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T00:00:00Z"));
        payment.attachReservationConfirmation(reservationId, participant.getId());
        payment = paymentRepository.saveAndFlush(payment);

        Refund refund = transactionService.createRequested(reservationId, participant.getId(), "test").refund();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        // cancellationId를 미리 채워, 동시 완료 단계에서 findWithLockByCancellationId가 존재하는
        // unique 값을 정확히 매칭하는 행 락만 얻도록 한다. 이렇게 하지 않으면 완료 단계에서 3개
        // 트랜잭션이 서로 다른 cancellation_id(아직 전부 NULL)를 그 unique 인덱스에 동시에 써
        // 넣으려 하면서 갭 락끼리 얽혀 데드락이 나는데, 이는 Issue #259(Reservation 전이 결함)와
        // 무관한 별도 결함이라 이 재현 테스트의 범위 밖이다(별도로 보고한다).
        refundCompletionService.markProcessingFromWebhook(payment.getPaymentId(), "cancel-" + payment.getPaymentId());
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
