package com.bobfull.payment.infrastructure.portone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bobfull.payment.application.port.PortOnePaymentPort.PortOnePayment;
import io.portone.sdk.server.PortOneClient;
import io.portone.sdk.server.common.Currency;
import io.portone.sdk.server.payment.PaidPayment;
import io.portone.sdk.server.payment.Payment;
import io.portone.sdk.server.payment.PaymentAmount;
import io.portone.sdk.server.payment.PaymentClient;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortOneSdkPaymentAdapterTest {
    @Mock private PortOneClient portOneClient;
    @Mock private PaymentClient paymentClient;
    @Mock private PaidPayment paidPayment;
    @Mock private Currency currency;

    @Test
    void PortOne_완료_응답을_내부_결제_모델로_변환한다() {
        // given
        given(portOneClient.getPayment()).willReturn(paymentClient);
        given(paymentClient.getPayment("payment-id")).willReturn(CompletableFuture.completedFuture(paidPayment));
        given(paidPayment.getId()).willReturn("payment-id");
        given(paidPayment.getAmount()).willReturn(new PaymentAmount(10000L, 0L, null, null, 0L, 10000L, 0L, 0L));
        given(paidPayment.getCurrency()).willReturn(currency);
        given(currency.getValue()).willReturn("KRW");
        PortOneSdkPaymentAdapter reader = new PortOneSdkPaymentAdapter(portOneClient);

        // when
        PortOnePayment result = reader.read("payment-id");

        // then
        assertThat(result.paymentId()).isEqualTo("payment-id");
        assertThat(result.paid()).isTrue();
        assertThat(result.amount()).hasToString("10000");
        assertThat(result.currency()).isEqualTo("KRW");
    }

    @Test
    void PortOne_미완료_응답은_paid_false로_변환한다() {
        // given
        Payment payment = org.mockito.Mockito.mock(Payment.class);
        given(portOneClient.getPayment()).willReturn(paymentClient);
        given(paymentClient.getPayment("payment-id")).willReturn(CompletableFuture.completedFuture(payment));
        PortOneSdkPaymentAdapter reader = new PortOneSdkPaymentAdapter(portOneClient);

        // when
        PortOnePayment result = reader.read("payment-id");

        // then
        assertThat(result.paymentId()).isEqualTo("payment-id");
        assertThat(result.paid()).isFalse();
        assertThat(result.amount()).isNull();
        assertThat(result.currency()).isNull();
    }
}
