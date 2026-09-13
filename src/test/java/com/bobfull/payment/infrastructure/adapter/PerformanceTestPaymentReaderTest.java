package com.bobfull.payment.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.application.port.PortOnePaymentReader.PortOnePayment;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerformanceTestPaymentReaderTest {
    @Mock private PaymentRepository paymentRepository;
    @Mock private Payment payment;

    @Test
    void 저장된_Payment의_금액과_통화를_그대로_PAID로_반환한다() {
        // given
        given(paymentRepository.findByPaymentId("payment-id")).willReturn(Optional.of(payment));
        given(payment.getAmount()).willReturn(new BigDecimal("10000.00"));
        given(payment.getCurrency()).willReturn("KRW");
        PerformanceTestPaymentReader reader = new PerformanceTestPaymentReader(paymentRepository);

        // when
        PortOnePayment result = reader.read("payment-id");

        // then
        assertThat(result.paymentId()).isEqualTo("payment-id");
        assertThat(result.paid()).isTrue();
        assertThat(result.amount()).isEqualTo(new BigDecimal("10000.00"));
        assertThat(result.currency()).isEqualTo("KRW");
    }

    @Test
    void 존재하지_않는_paymentId는_예외를_던진다() {
        // given
        given(paymentRepository.findByPaymentId("missing")).willReturn(Optional.empty());
        PerformanceTestPaymentReader reader = new PerformanceTestPaymentReader(paymentRepository);

        // when, then
        assertThatThrownBy(() -> reader.read("missing")).isInstanceOf(CustomException.class);
    }
}
