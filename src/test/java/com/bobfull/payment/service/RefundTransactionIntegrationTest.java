package com.bobfull.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.payment.adapter.ReservationCancellationRefundAdapter;
import com.bobfull.payment.entity.Payment;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.payment.entity.Refund;
import com.bobfull.payment.entity.RefundStatus;
import com.bobfull.payment.port.PortOneRefundRequester;
import com.bobfull.payment.repository.PaymentRepository;
import com.bobfull.payment.repository.RefundRepository;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.PaymentErrorCode;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort.RefundRequestCommand;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:refund-transaction-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "jwt.secret=refund-transaction-test-secret-key-please-keep-long", "jwt.access-token-expiration-seconds=1800", "portone.api-secret=test", "portone.store-id=test", "portone.webhook-secret=dGVzdA=="})
@ContextConfiguration(classes = RefundTransactionIntegrationTest.Config.class)
class RefundTransactionIntegrationTest {
    @Autowired private OuterRollbackProbe outerRollbackProbe;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private ReservationCancellationRefundAdapter adapter;
    @Autowired private SequencedRequester requester;
    @Autowired private RefundTransactionService transactionService;
    @Autowired private RefundCompletionService refundCompletionService;
    @Autowired private RefundWebhookService refundWebhookService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationParticipantRepository reservationParticipantRepository;
    @Autowired private DelayedCompletionProbe delayedCompletionProbe;
    @MockitoSpyBean private RefundIdempotencyKeyGenerator keyGenerator;
    private Reservation activeReservation;
    private final Map<Long, Long> participantIds = new ConcurrentHashMap<>();

    @AfterEach void clean() { requester.reset(); refundRepository.deleteAll(); paymentRepository.deleteAll(); reservationParticipantRepository.deleteAll(); reservationRepository.deleteAll(); activeReservation = null; participantIds.clear(); }

    @Test
    void 앞선_외부_환불_성공은_뒤_실패와_예약트랜잭션_롤백_후에도_보존된다() {
        Payment first = paid(1L); Payment second = paid(2L);
        assertThatThrownBy(() -> outerRollbackProbe.cancel(activeReservation.getId(), List.of(participantIds.get(1L), participantIds.get(2L)))).isInstanceOf(RuntimeException.class);
        assertThat(refundRepository.findByPayment_Id(first.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(paymentRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundRepository.findByPayment_Id(second.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(paymentRepository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 참여자_3명_중_두번째가_실패해도_세번째는_시도되어_완료된다() {
        Payment first = paid(1L);
        Payment second = paid(2L);
        Payment third = paid(3L);
        List<Long> ids = List.of(participantIds.get(1L), participantIds.get(2L), participantIds.get(3L));

        assertThatThrownBy(() -> adapter.requestRefunds(new RefundRequestCommand(activeReservation.getId(), ids, 1L, "test")))
                .isInstanceOf(RuntimeException.class);

        assertThat(refundRepository.findByPayment_Id(first.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refundRepository.findByPayment_Id(second.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refundRepository.findByPayment_Id(third.getId())).isPresent();
        assertThat(refundRepository.findByPayment_Id(third.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(requester.calls()).isEqualTo(3);
    }

    @Test
    void 동시_최초_환불_생성_요청_중_하나만_성공하고_나머지는_REFUND_PROCESSING으로_거절된다() throws Exception {
        Payment payment = paid(1L);
        var executor = Executors.newFixedThreadPool(2);
        int successCount = 0;
        Exception rejected = null;
        try {
            var first = executor.submit(() -> transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test"));
            var second = executor.submit(() -> transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test"));
            for (var future : List.of(first, second)) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    successCount++;
                } catch (java.util.concurrent.ExecutionException e) {
                    rejected = (Exception) e.getCause();
                }
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(successCount).isEqualTo(1);
        assertThat(rejected).isInstanceOf(CustomException.class);
        assertThat(((CustomException) rejected).getErrorCode()).isEqualTo(PaymentErrorCode.REFUND_PROCESSING);
        assertThat(refundRepository.findByPayment_Id(payment.getId())).isPresent();
        assertThat(refundRepository.count()).isEqualTo(1);
    }

    @Test
    void 서로_다른_Refund는_서로_다른_idempotencyKey를_가진다() {
        paid(1L);
        paid(2L);

        Refund first = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();
        Refund second = transactionService.createRequested(activeReservation.getId(), participantIds.get(2L), "test").refund();

        assertThat(first.getIdempotencyKey()).isNotBlank();
        assertThat(second.getIdempotencyKey()).isNotBlank();
        assertThat(first.getIdempotencyKey()).isNotEqualTo(second.getIdempotencyKey());
    }

    @Test
    void 키_생성기는_신규_Refund_생성시에만_정확히_한번_호출되고_기존_Refund_재조회시_재호출되지_않는다() {
        paid(1L);

        transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test");
        verify(keyGenerator, times(1)).generate();

        assertThatThrownBy(() -> transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test again"))
                .isInstanceOf(CustomException.class);
        verify(keyGenerator, times(1)).generate();
    }

    @Test
    void 동일_Payment_동시_환불은_Refund_한건과_외부호출_한번으로_수렴한다() throws Exception {
        Payment payment = paid(1L);
        requester.blockFirstCall();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> adapter.requestRefunds(new RefundRequestCommand(activeReservation.getId(), List.of(participantIds.get(1L)), 1L, "test")));
            assertThat(requester.firstCallEntered.await(15, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> adapter.requestRefunds(new RefundRequestCommand(activeReservation.getId(), List.of(participantIds.get(1L)), 1L, "test")));
            requester.releaseFirstCall.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
        } finally { executor.shutdownNow(); }
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(requester.calls()).isEqualTo(1);
        assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void 완료된_Refund_중복요청은_외부환불_재호출없이_기존완료결과를_반환한다() {
        Payment payment = paid(1L);
        RefundRequestCommand command = new RefundRequestCommand(activeReservation.getId(), List.of(participantIds.get(1L)), 1L, "test");

        adapter.requestRefunds(command);
        var repeated = adapter.requestRefunds(command);

        assertThat(repeated).singleElement().extracting(result -> result.refundStatus()).isEqualTo(RefundStatus.COMPLETED.name());
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(requester.calls()).isEqualTo(1);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void 타임아웃은_실패로_확정하지_않고_자동_재호출하지_않는다() {
        Payment payment = paid(1L); requester.timeoutNextCall();
        RefundRequestCommand command = new RefundRequestCommand(activeReservation.getId(), List.of(participantIds.get(1L)), 1L, "test");
        assertThatThrownBy(() -> adapter.requestRefunds(command)).isInstanceOf(CustomException.class);

        Refund refund = refundRepository.findByPayment_Id(payment.getId()).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(requester.calls()).isEqualTo(1);
        String idempotencyKey = refund.getIdempotencyKey();
        String requestReason = refund.getRequestReason();

        assertThatThrownBy(() -> adapter.requestRefunds(command)).isInstanceOf(CustomException.class);

        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(requester.calls()).isEqualTo(1);
        Refund refundAfterRetry = refundRepository.findByPayment_Id(payment.getId()).orElseThrow();
        assertThat(refundAfterRetry.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(refundAfterRetry.getRequestReason()).isEqualTo(requestReason);
    }

    @Test
    void 결과불명확_통신오류는_실패로_확정하지_않고_자동_재호출하지_않는다() {
        Payment payment = paid(1L); requester.connectionResetNextCall();
        RefundRequestCommand command = new RefundRequestCommand(activeReservation.getId(), List.of(participantIds.get(1L)), 1L, "test");
        assertThatThrownBy(() -> adapter.requestRefunds(command)).isInstanceOf(CustomException.class);

        Refund refund = refundRepository.findByPayment_Id(payment.getId()).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(requester.calls()).isEqualTo(1);
        String idempotencyKey = refund.getIdempotencyKey();
        String requestReason = refund.getRequestReason();

        assertThatThrownBy(() -> adapter.requestRefunds(command)).isInstanceOf(CustomException.class);

        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(requester.calls()).isEqualTo(1);
        Refund refundAfterRetry = refundRepository.findByPayment_Id(payment.getId()).orElseThrow();
        assertThat(refundAfterRetry.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(refundAfterRetry.getRequestReason()).isEqualTo(requestReason);
    }

    @Test
    void Cancelled_웹훅_완료는_Refund_Payment_Participant_Reservation까지_반영한다() {
        Payment payment = paid(1L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();
        refundCompletionService.reflectExternalResult(refund.getId(), "cancel-webhook", false);
        refundWebhookService.complete(payment.getPaymentId(), "cancel-webhook");
        assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        ReservationParticipant participant = reservationParticipantRepository.findById(participantIds.get(1L)).orElseThrow();
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(participant.getCancelledAt()).isNotNull();
        assertThat(reservationRepository.findById(activeReservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void 즉시응답_완료후_같은_Cancelled_웹훅도_예약완료를_멱등처리한다() {
        Payment payment = paid(1L);
        adapter.requestRefunds(new RefundRequestCommand(activeReservation.getId(), List.of(participantIds.get(1L)), 1L, "test"));
        refundWebhookService.complete(payment.getPaymentId(), "cancel-" + payment.getPaymentId());
        ReservationParticipant participant = reservationParticipantRepository.findById(participantIds.get(1L)).orElseThrow();
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(participant.getCancelledAt()).isNotNull();
        assertThat(reservationRepository.findById(activeReservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void 웹훅과_즉시응답_동시완료도_Participant를_한번만_완료한다() throws Exception {
        Payment payment = paid(1L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();
        refundCompletionService.reflectExternalResult(refund.getId(), "cancel-race", false);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var immediate = executor.submit(() -> refundCompletionService.reflectExternalResult(refund.getId(), "cancel-race", true));
            var webhook = executor.submit(() -> refundWebhookService.complete(payment.getPaymentId(), "cancel-race"));
            immediate.get(5, TimeUnit.SECONDS);
            webhook.get(5, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        ReservationParticipant participant = reservationParticipantRepository.findById(participantIds.get(1L)).orElseThrow();
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(participant.getCancelledAt()).isNotNull();
        assertThat(reservationRepository.findById(activeReservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void 다른_CANCEL_REQUESTED가_남으면_웹훅_완료후에도_CANCELLING을_유지한다() {
        Payment first = paid(1L);
        paid(2L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();
        refundCompletionService.reflectExternalResult(refund.getId(), "cancel-first", false);
        refundWebhookService.complete(first.getPaymentId(), "cancel-first");
        assertThat(reservationParticipantRepository.findById(participantIds.get(1L)).orElseThrow().getParticipationStatus())
                .isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(reservationParticipantRepository.findById(participantIds.get(2L)).orElseThrow().getParticipationStatus())
                .isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(reservationRepository.findById(activeReservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.CANCELLING);
    }

    @Test
    void 존재하지않는_cancellationId_웹훅은_안전하게_무시한다() {
        org.assertj.core.api.Assertions.assertThatCode(
                () -> refundWebhookService.complete("missing-payment", "missing-cancellation"))
                .doesNotThrowAnyException();
    }

    @Test
    void 파싱_전_실패로_cancellationId가_없는_Refund도_웹훅이_paymentId로_찾아_완료한다() {
        Payment payment = paid(1L);
        requester.timeoutNextCall();
        assertThatThrownBy(() -> adapter.requestRefunds(new RefundRequestCommand(activeReservation.getId(), List.of(participantIds.get(1L)), 1L, "test")))
                .isInstanceOf(CustomException.class);
        assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getCancellationId()).isNull();
        assertThat(refundRepository.findByCancellationId("cancel-late")).isEmpty();

        refundWebhookService.complete(payment.getPaymentId(), "cancel-late");

        Refund refund = refundRepository.findByPayment_Id(payment.getId()).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getCancellationId()).isEqualTo("cancel-late");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        ReservationParticipant participant = reservationParticipantRepository.findById(participantIds.get(1L)).orElseThrow();
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(reservationRepository.findById(activeReservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void 다른_cancellationId_웹훅은_이미_추적중인_Refund를_바꾸지_않는다() {
        Payment payment = paid(1L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();
        refundCompletionService.reflectExternalResult(refund.getId(), "cancel-A", false);

        refundWebhookService.complete(payment.getPaymentId(), "cancel-B");

        Refund reloaded = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(reloaded.getCancellationId()).isEqualTo("cancel-A");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservationParticipantRepository.findById(participantIds.get(1L)).orElseThrow().getParticipationStatus())
                .isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
    }

    @Test
    void 완료_트랜잭션이_Refund_락을_쥔_동안_뒤늦은_CancelPending은_대기했다가_완료상태를_덮지_못한다() throws Exception {
        Payment payment = paid(1L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var completion = executor.submit(() ->
                    delayedCompletionProbe.completeAndWait(refund.getId(), "cancel-done", lockAcquired, releaseCommit));
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            // 완료 트랜잭션이 Refund 행 락을 쥔 채 커밋 전 대기 중이다. 뒤늦게 도착한 CancelPending은
            // 같은 행의 락을 기다려야 하므로 짧은 시간 안에 끝나지 않는 것으로 락이 실제 대기를
            // 강제하는지 확인한다.
            var latePending = executor.submit(() -> refundWebhookService.markProcessing(payment.getPaymentId(), "cancel-done"));
            assertThatThrownBy(() -> latePending.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseCommit.countDown();
            completion.get(5, TimeUnit.SECONDS);
            latePending.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Refund reloaded = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(reloaded.getCancellationId()).isEqualTo("cancel-done");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void 완료_트랜잭션이_Refund_락을_쥔_동안_뒤늦은_실패처리는_대기했다가_완료상태를_덮지_못한다() throws Exception {
        Payment payment = paid(1L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var completion = executor.submit(() ->
                    delayedCompletionProbe.completeAndWait(refund.getId(), "cancel-done", lockAcquired, releaseCommit));
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            var lateFailure = executor.submit(() -> { transactionService.markFailed(refund.getId()); return null; });
            assertThatThrownBy(() -> lateFailure.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            releaseCommit.countDown();
            completion.get(5, TimeUnit.SECONDS);
            lateFailure.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Refund reloaded = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(reloaded.getCancellationId()).isEqualTo("cancel-done");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void 완료_이후_같은_cancellationId의_뒤늦은_CancelPending은_순차적으로도_완료상태를_유지한다() {
        Payment payment = paid(1L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();
        refundWebhookService.complete(payment.getPaymentId(), "cancel-final");

        refundWebhookService.markProcessing(payment.getPaymentId(), "cancel-final");

        Refund reloaded = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(reloaded.getCancellationId()).isEqualTo("cancel-final");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void FAILED_Refund에_늦은_Cancelled_웹훅이와도_Payment와_예약완료상태를_바꾸지않는다() {
        Payment payment = paid(1L);
        var refund = transactionService.createRequested(activeReservation.getId(), participantIds.get(1L), "test").refund();
        transactionService.markFailed(refund.getId());

        refundWebhookService.complete(payment.getPaymentId(), "cancel-after-failure");

        assertThat(refundRepository.findById(refund.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reservationParticipantRepository.findById(participantIds.get(1L)).orElseThrow().getParticipationStatus())
                .isEqualTo(ParticipationStatus.CANCEL_REQUESTED);
        assertThat(reservationRepository.findById(activeReservation.getId()).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.CANCELLING);
    }

    @Test
    void 예약완료_반영에_실패하면_Refund와_Payment_완료상태도_함께_롤백한다() {
        Payment payment = Payment.createReady("rollback-" + UUID.randomUUID(), 1L, 1L, 999L,
                PaymentPurpose.JOIN, 1, BigDecimal.valueOf(1000), Instant.parse("2026-12-01T00:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T00:00:00Z"));
        payment.attachReservationConfirmation(999L, 888L);
        payment = paymentRepository.saveAndFlush(payment);
        Refund refund = transactionService.createRequested(999L, 888L, "test").refund();
        Logger logger = (Logger) LoggerFactory.getLogger(RefundTransactionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> refundCompletionService.reflectExternalResult(refund.getId(), "cancel-rollback", true))
                    .isInstanceOf(CustomException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(refundRepository.findById(refund.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(appender.list).noneMatch(event ->
                event.getFormattedMessage().contains("event=REFUND_COMPLETED"));
    }

    @Test
    void PortOne_환불_성공_직후_예약완료_반영이_실패하면_PortOne_실패와_구분되는_재조정_필요_오류를_반환한다() {
        Payment payment = Payment.createReady("rollback-adapter-" + UUID.randomUUID(), 1L, 1L, 999L,
                PaymentPurpose.JOIN, 1, BigDecimal.valueOf(1000), Instant.parse("2026-12-01T00:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T00:00:00Z"));
        payment.attachReservationConfirmation(999L, 888L);
        payment = paymentRepository.saveAndFlush(payment);

        assertThatThrownBy(() -> adapter.requestRefunds(new RefundRequestCommand(999L, List.of(888L), 1L, "test")))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(PaymentErrorCode.REFUND_RECONCILIATION_REQUIRED);

        assertThat(refundRepository.findByPayment_Id(payment.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(requester.calls()).isEqualTo(1);
    }

    private Payment paid(Long participantId) {
        if (activeReservation == null) {
            activeReservation = reservationRepository.saveAndFlush(Reservation.create(1L, 1L));
            activeReservation.startCancelling();
            activeReservation = reservationRepository.saveAndFlush(activeReservation);
        }
        ReservationParticipant participant = ReservationParticipant.create(activeReservation.getId(), participantId, 1);
        participant.requestCancel("test");
        participant = reservationParticipantRepository.saveAndFlush(participant);
        participantIds.put(participantId, participant.getId());
        Payment payment = Payment.createReady("refund-" + UUID.randomUUID(), participantId, 1L, activeReservation.getId(), PaymentPurpose.JOIN, 1, BigDecimal.valueOf(1000), Instant.parse("2026-12-01T00:00:00Z"));
        payment.complete(Instant.parse("2026-08-01T00:00:00Z")); payment.attachReservationConfirmation(activeReservation.getId(), participant.getId());
        return paymentRepository.saveAndFlush(payment);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean @Primary SequencedRequester requester() { return new SequencedRequester(); }
        @Bean OuterRollbackProbe outerRollbackProbe(ReservationCancellationRefundAdapter adapter) { return new OuterRollbackProbe(adapter); }
        @Bean DelayedCompletionProbe delayedCompletionProbe(RefundRepository refundRepository) { return new DelayedCompletionProbe(refundRepository); }
    }
    /**
     * Refund 행 락을 쥔 채 커밋 직전에 멈춰, 그 사이 다른 트랜잭션이 같은 행에 대한 잠금 조회를
     * 시도하면 실제로 대기하는지(=락이 진짜 동시 갱신을 직렬화하는지) 결정적으로 검증하기 위한
     * 테스트 전용 프로브다.
     */
    static class DelayedCompletionProbe {
        private final RefundRepository refundRepository;
        DelayedCompletionProbe(RefundRepository refundRepository) { this.refundRepository = refundRepository; }
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void completeAndWait(Long refundId, String cancellationId, CountDownLatch lockAcquired, CountDownLatch releaseCommit) {
            Refund refund = refundRepository.findWithLockById(refundId).orElseThrow();
            refund.complete(cancellationId, Instant.now());
            if (refund.getPayment().getStatus() == PaymentStatus.PAID) refund.getPayment().markRefunded();
            lockAcquired.countDown();
            try {
                if (!releaseCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release latch timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
    static class SequencedRequester implements PortOneRefundRequester {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch firstCallEntered;
        private volatile CountDownLatch releaseFirstCall;
        private volatile boolean timeoutNext;
        private volatile boolean connectionResetNext;
        public RefundResult request(String paymentId, BigDecimal amount, String reason, String idempotencyKey) {
            int call = calls.incrementAndGet();
            if (timeoutNext) { timeoutNext = false; throw new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException("timeout")); }
            if (connectionResetNext) { connectionResetNext = false; throw new java.util.concurrent.CompletionException(new java.io.IOException("connection reset")); }
            if (call == 2) throw new ExplicitRefundFailureException("PortOne explicitly rejected the refund");
            CountDownLatch entered = firstCallEntered; if (entered != null) { entered.countDown(); try { releaseFirstCall.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
            return new RefundResult("cancel-" + paymentId, true);
        }
        public boolean isCancellationCompleted(String paymentId, String cancellationId) { return true; }
        void blockFirstCall() { firstCallEntered = new CountDownLatch(1); releaseFirstCall = new CountDownLatch(1); }
        int calls() { return calls.get(); }
        void timeoutNextCall() { timeoutNext = true; }
        void connectionResetNextCall() { connectionResetNext = true; }
        void reset() { calls.set(0); firstCallEntered = null; releaseFirstCall = null; timeoutNext = false; connectionResetNext = false; }
    }
    static class OuterRollbackProbe {
        private final ReservationCancellationRefundAdapter adapter;
        OuterRollbackProbe(ReservationCancellationRefundAdapter adapter) { this.adapter = adapter; }
        @Transactional public void cancel(Long reservationId, List<Long> ids) { adapter.requestRefunds(new RefundRequestCommand(reservationId, ids, 1L, "test")); }
    }
}
