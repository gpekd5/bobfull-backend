package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.exception.PaymentExpiredException;
import com.bobfull.payment.application.port.PortOnePaymentPort;
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

@ExtendWith(MockitoExtension.class)
class PaymentWebhookCompensationLogTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PortOnePaymentPort portOnePaymentPort;
    @Mock private PaymentCompletionTransactionService transactionService;
    @Mock private BusinessMetricRecorder businessMetricRecorder;

    @Test
    void 외부_PAID와_내부_만료가_갈리면_보상필요_구조화로그의_필수필드를_기록한다() {
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T00:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentPort.read("payment-id"))
                .willReturn(new PortOnePaymentPort.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        given(transactionService.complete("payment-id")).willAnswer(invocation -> {
            ReflectionTestUtils.setField(payment, "status", PaymentStatus.EXPIRED);
            throw new PaymentExpiredException(PaymentStatus.EXPIRED, payment.getExpiresAt());
        });
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentPort,
                transactionService, Clock.fixed(Instant.parse("2026-07-27T23:59:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentCompletionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.catchThrowable(() -> service.completeFromWebhook("payment-id"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=PAYMENT_COMPENSATION_REQUIRED");
            assertThat(event.getFormattedMessage()).contains("paymentId=payment-id");
            assertThat(event.getFormattedMessage()).contains("externalStatus=PAID");
            assertThat(event.getFormattedMessage()).contains("internalStatus=EXPIRED");
            assertThat(event.getFormattedMessage()).contains("expiresAt=2026-07-28T00:00:00Z");
            assertThat(event.getFormattedMessage()).contains("reason=PAYMENT_EXPIRED");
        });
    }

    @Test
    void 외부_PAID_이후_내부_업무예외가_발생하면_보상필요_구조화로그를_기록한다() {
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T00:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentPort.read("payment-id"))
                .willReturn(new PortOnePaymentPort.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        given(transactionService.complete("payment-id"))
                .willThrow(new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentPort,
                transactionService, Clock.fixed(Instant.parse("2026-07-27T23:59:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentCompletionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.catchThrowable(() -> service.completeFromWebhook("payment-id"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=PAYMENT_COMPENSATION_REQUIRED");
            assertThat(event.getFormattedMessage()).contains("paymentId=payment-id");
            assertThat(event.getFormattedMessage()).contains("externalStatus=PAID");
            assertThat(event.getFormattedMessage()).contains("internalStatus=UNKNOWN");
            assertThat(event.getFormattedMessage()).contains("reason=PAYMENT_VERIFICATION_FAILED");
        });
    }

    @Test
    void 외부_PAID_이후_내부_런타임예외가_발생하면_보상필요_구조화로그와_스택트레이스를_기록한다() {
        Payment payment = Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.valueOf(10000), Instant.parse("2026-07-28T00:00:00Z"));
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(portOnePaymentPort.read("payment-id"))
                .willReturn(new PortOnePaymentPort.PortOnePayment("payment-id", true, BigDecimal.valueOf(10000), "KRW"));
        given(transactionService.complete("payment-id")).willThrow(new IllegalStateException("db flush failed"));
        PaymentCompletionService service = new PaymentCompletionService(paymentRepository, portOnePaymentPort,
                transactionService, Clock.fixed(Instant.parse("2026-07-27T23:59:00Z"), ZoneOffset.UTC),
                businessMetricRecorder);
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentCompletionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.catchThrowable(() -> service.completeFromWebhook("payment-id"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=PAYMENT_COMPENSATION_REQUIRED");
            assertThat(event.getFormattedMessage()).contains("paymentId=payment-id");
            assertThat(event.getFormattedMessage()).contains("externalStatus=PAID");
            assertThat(event.getFormattedMessage()).contains("internalStatus=UNKNOWN");
            assertThat(event.getFormattedMessage()).contains("reason=IllegalStateException");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }
}
