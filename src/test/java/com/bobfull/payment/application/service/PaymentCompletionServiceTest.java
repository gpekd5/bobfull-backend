package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.application.port.PortOnePaymentReader;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentCompletionServiceTest {
    @Mock private PaymentRepository paymentRepository;
    @Mock private PortOnePaymentReader portOnePaymentReader;
    @Mock private PaymentCompletionTransactionService transactionService;
    @Mock private BusinessMetricRecorder businessMetricRecorder;

    @Test
    void 존재하지_않는_Payment은_결제_검증에_실패하고_외부_호출을_수행하지_않는다() {
        // given
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.empty());
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.systemUTC(), businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
        verifyNoInteractions(portOnePaymentReader, transactionService);
    }

    @Test
    void Payment_소유자가_아니면_결제_검증에_실패하고_외부_호출을_수행하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.systemUTC(), businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 2L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        verifyNoInteractions(portOnePaymentReader, transactionService);
    }

    @Test
    void 만료된_Payment은_예약확정_트랜잭션을_시작하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T00:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        given(transactionService.complete("payment-id", 1L))
                .willThrow(new com.bobfull.payment.domain.exception.PaymentExpiredException(PaymentStatus.READY, payment.getExpiresAt()));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_EXPIRED);
        org.mockito.Mockito.verify(transactionService).complete("payment-id", 1L);
    }

    @Test
    void 이미_완료된_Payment은_PortOne_재조회와_예약확정_트랜잭션을_수행하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        payment.complete(Instant.parse("2026-07-28T00:00:00Z"));
        payment.attachReservationConfirmation(10L, 20L);
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        var result = service.complete("payment-id", 1L);

        // then
        assertThat(result.reservationId()).isEqualTo(10L);
        assertThat(result.participationId()).isEqualTo(20L);
        verifyNoInteractions(portOnePaymentReader, transactionService);
    }

    @Test
    void 내부_금액의_scale만_다르면_정상_검증을_통과한다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                new BigDecimal("10000.00"), Instant.parse("2026-07-28T01:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        var expected = new PaymentCompletionTransactionService.PaymentCompletionResult(payment, 10L, 20L);
        given(transactionService.complete("payment-id", 1L)).willReturn(expected);
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        var result = service.complete("payment-id", 1L);

        // then
        assertThat(result).isSameAs(expected);
    }

    @Test
    void PortOne_승인_금액이_내부_금액과_다르면_결제_검증에_실패하고_트랜잭션을_시작하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("payment-id", true, BigDecimal.valueOf(9000), "KRW"));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        verifyNoInteractions(transactionService);
    }

    @Test
    void PortOne_통화가_KRW가_아니면_결제_검증에_실패하고_트랜잭션을_시작하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "USD"));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        verifyNoInteractions(transactionService);
    }

    @Test
    void 내부와_외부_통화가_다르면_결제_검증에_실패하고_트랜잭션을_시작하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        ReflectionTestUtils.setField(payment, "currency", "USD");
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        verifyNoInteractions(transactionService);
    }

    @Test
    void PortOne_결제_상태가_PAID가_아니면_결제_검증에_실패하고_트랜잭션을_시작하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("payment-id", false, null, null));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        verifyNoInteractions(transactionService);
    }

    @Test
    void PortOne_paymentId가_요청_paymentId와_다르면_결제_검증에_실패하고_트랜잭션을_시작하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("other-payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        verifyNoInteractions(transactionService);
    }

    @Test
    void FAILED는_결제완료를_거절하고_예약확정_트랜잭션을_수행하지_않는다() {
        // given
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T01:00:00Z"));
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.FAILED);
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:01:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        // when
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        verifyNoInteractions(portOnePaymentReader, transactionService);
    }

    @Test
    void PortOne_재조회_후_만료되면_잠금_트랜잭션에서_PAYMENT_EXPIRED로_거절한다() {
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T00:00:01Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentReader.read("payment-id"))
                .willReturn(new PortOnePaymentReader.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        given(transactionService.complete("payment-id", 1L))
                .willThrow(new com.bobfull.payment.domain.exception.PaymentExpiredException(PaymentStatus.READY, payment.getExpiresAt()));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentReader,
                transactionService, Clock.fixed(Instant.parse("2026-07-28T00:00:01Z"), ZoneOffset.UTC),
                businessMetricRecorder);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.complete("payment-id", 1L));

        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_EXPIRED);
        org.mockito.Mockito.verify(transactionService).complete("payment-id", 1L);
    }
}
