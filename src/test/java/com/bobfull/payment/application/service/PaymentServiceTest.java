package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.payment.application.command.CreateReadyPaymentCommand;
import com.bobfull.payment.application.result.CreateReadyPaymentResult;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * READY Payment 생성의 저장값, 만료 시각과 중복 식별자 처리를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void READY_Payment을_생성하면_KRW와_10분_후_만료시각을_저장한다() {
        // given
        CreateReadyPaymentCommand command = validCommand();
        given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        CreateReadyPaymentResult result = paymentService.createReadyPayment(command);

        // then
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        org.mockito.Mockito.verify(paymentRepository).saveAndFlush(captor.capture());
        Payment savedPayment = captor.getValue();
        assertThat(savedPayment.getMemberId()).isEqualTo(1L);
        assertThat(savedPayment.getTimeSlotId()).isEqualTo(10L);
        assertThat(savedPayment.getReservationId()).isNull();
        assertThat(savedPayment.getPurpose()).isEqualTo(PaymentPurpose.CREATE);
        assertThat(savedPayment.getPartySize()).isEqualTo(2);
        assertThat(savedPayment.getAmount()).isEqualByComparingTo("30000");
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(savedPayment.getCurrency()).isEqualTo(Payment.CURRENCY_KRW);
        assertThat(savedPayment.getExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(600));
        assertThat(UUID.fromString(savedPayment.getPaymentId())).isNotNull();
        assertThat(result.paymentId()).isEqualTo(savedPayment.getPaymentId());
        assertThat(result.expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(600));
    }

    @Test
    void 양수가_아닌_partySize나_amount로_READY_Payment을_생성하면_거절한다() {
        // when & then
        assertThatThrownBy(() -> Payment.createReady(
                "payment-id",
                1L,
                10L,
                null,
                PaymentPurpose.CREATE,
                0,
                BigDecimal.valueOf(30000),
                FIXED_NOW.plusSeconds(600)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Payment.createReady(
                "payment-id",
                1L,
                10L,
                null,
                PaymentPurpose.CREATE,
                2,
                BigDecimal.ZERO,
                FIXED_NOW.plusSeconds(600)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Payment.createReady(
                "payment-id",
                1L,
                10L,
                null,
                PaymentPurpose.JOIN,
                2,
                BigDecimal.valueOf(30000),
                FIXED_NOW.plusSeconds(600)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void JOIN_READY_Payment을_생성하면_대상_Reservation을_저장한다() {
        // given
        given(paymentRepository.saveAndFlush(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));
        CreateReadyPaymentCommand command = new CreateReadyPaymentCommand(
                1L,
                10L,
                20L,
                PaymentPurpose.JOIN,
                2,
                BigDecimal.valueOf(30000)
        );

        // when
        paymentService.createReadyPayment(command);

        // then
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        org.mockito.Mockito.verify(paymentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getReservationId()).isEqualTo(20L);
    }

    @Test
    void 외부_paymentId_중복으로_DB_제약을_위반하면_도메인_예외로_변환한다() {
        // given
        given(paymentRepository.saveAndFlush(any(Payment.class)))
                .willThrow(new DataIntegrityViolationException("duplicate portone_payment_id"));

        // when
        Throwable result = catchThrowable(() -> paymentService.createReadyPayment(validCommand()));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(PaymentErrorCode.DUPLICATE_PAYMENT_ID);
    }

    private CreateReadyPaymentCommand validCommand() {
        return new CreateReadyPaymentCommand(
                1L,
                10L,
                null,
                PaymentPurpose.CREATE,
                2,
                BigDecimal.valueOf(30000)
        );
    }
}
