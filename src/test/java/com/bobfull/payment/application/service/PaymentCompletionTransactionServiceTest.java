package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.PaymentErrorCode;
import com.bobfull.payment.application.port.ReservationConfirmationPort;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class PaymentCompletionTransactionServiceTest {
    @Mock private PaymentRepository paymentRepository;
    @Mock private ReservationConfirmationPort reservationConfirmationPort;
    @Mock private BusinessMetricRecorder businessMetricRecorder;

    @Test
    void 잠금_획득후_READY_Payment을_완료하고_Port_결과를_응답과_엔티티에_연결한다() {
        // given
        Payment payment = readyPayment();
        given(paymentRepository.findWithLockByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(reservationConfirmationPort.confirm(payment))
                .willReturn(new ReservationConfirmationPort.ReservationConfirmationResult(10L, 20L));
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        PaymentCompletionTransactionService service = new PaymentCompletionTransactionService(paymentRepository,
                reservationConfirmationPort, Clock.fixed(now, ZoneOffset.UTC), businessMetricRecorder);

        // when
        var result = service.complete("payment-id", 1L);

        // then
        assertThat(result.reservationId()).isEqualTo(10L);
        assertThat(result.participationId()).isEqualTo(20L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(now);
        assertThat(payment.getReservationId()).isEqualTo(10L);
        assertThat(payment.getReservationParticipantId()).isEqualTo(20L);
        verify(reservationConfirmationPort).confirm(payment);
    }

    @Test
    void READY_Payment를_완료하면_afterCommit에서_PAYMENT_COMPLETED_구조화로그를_남긴다() {
        // given
        Payment payment = readyPayment();
        given(paymentRepository.findWithLockByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(reservationConfirmationPort.confirm(payment))
                .willReturn(new ReservationConfirmationPort.ReservationConfirmationResult(10L, 20L));
        PaymentCompletionTransactionService service = new PaymentCompletionTransactionService(paymentRepository,
                reservationConfirmationPort, Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentCompletionTransactionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        TransactionSynchronizationManager.initSynchronization();

        try {
            // when
            service.complete("payment-id", 1L);
            assertThat(appender.list).isEmpty();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            logger.detachAppender(appender);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        // then
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=PAYMENT_COMPLETED");
            assertThat(event.getFormattedMessage()).contains("paymentId=payment-id");
            assertThat(event.getFormattedMessage()).contains("memberId=1");
            assertThat(event.getFormattedMessage()).contains("reservationId=10");
            assertThat(event.getFormattedMessage()).contains("participantId=20");
            assertThat(event.getFormattedMessage()).contains("afterStatus=PAID");
        });
    }

    @Test
    void 잠금_획득후_이미_PAID인_Payment은_기존_결과를_반환하고_Port를_호출하지_않는다() {
        // given
        Payment payment = readyPayment();
        payment.complete(Instant.parse("2026-07-28T00:00:00Z"));
        payment.attachReservationConfirmation(10L, 20L);
        given(paymentRepository.findWithLockByPaymentId("payment-id")).willReturn(Optional.of(payment));
        PaymentCompletionTransactionService service = new PaymentCompletionTransactionService(paymentRepository,
                reservationConfirmationPort, Clock.systemUTC(), businessMetricRecorder);

        // when
        var result = service.complete("payment-id", 1L);

        // then
        assertThat(result.reservationId()).isEqualTo(10L);
        assertThat(result.participationId()).isEqualTo(20L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verifyNoInteractions(reservationConfirmationPort);
    }

    @Test
    void 잠금_획득후_READY가_아닌_Payment은_검증에_실패하고_Port를_호출하지_않는다() {
        // given
        Payment payment = readyPayment();
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.FAILED);
        given(paymentRepository.findWithLockByPaymentId("payment-id")).willReturn(Optional.of(payment));
        PaymentCompletionTransactionService service = new PaymentCompletionTransactionService(paymentRepository,
                reservationConfirmationPort, Clock.systemUTC(), businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(reservationConfirmationPort);
    }

    @Test
    void 잠금_획득후_소유권이_일치하지_않으면_접근을_거부하고_Port를_호출하지_않는다() {
        // given
        Payment payment = readyPayment();
        given(paymentRepository.findWithLockByPaymentId("payment-id")).willReturn(Optional.of(payment));
        PaymentCompletionTransactionService service = new PaymentCompletionTransactionService(paymentRepository,
                reservationConfirmationPort, Clock.systemUTC(), businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 2L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        verifyNoInteractions(reservationConfirmationPort);
    }

    @Test
    void 락_획득_대기중_만료된_Payment은_READY를_유지하고_Port를_호출하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T00:00:00Z"));
        given(paymentRepository.findWithLockByPaymentId("payment-id")).willReturn(Optional.of(payment));
        PaymentCompletionTransactionService service = new PaymentCompletionTransactionService(paymentRepository,
                reservationConfirmationPort, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_EXPIRED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        verifyNoInteractions(reservationConfirmationPort);
    }

    private Payment readyPayment() {
        return Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
    }
}
