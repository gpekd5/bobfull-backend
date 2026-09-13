package com.bobfull.payment.infrastructure.adapter;

import com.bobfull.payment.application.port.PortOnePaymentReader;
import io.portone.sdk.server.PortOneClient;
import io.portone.sdk.server.payment.PaidPayment;
import io.portone.sdk.server.payment.Payment;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** PortOne SDK 타입을 결제 도메인 내부 모델로 변환한다. */
@Component
public class PortOneSdkPaymentReader implements PortOnePaymentReader {
    private final PortOneClient portOneClient;

    public PortOneSdkPaymentReader(PortOneClient portOneClient) {
        this.portOneClient = portOneClient;
    }

    @Override
    public PortOnePayment read(String paymentId) {
        Payment payment = portOneClient.getPayment().getPayment(paymentId).join();
        if (payment instanceof PaidPayment paidPayment) {
            return new PortOnePayment(paidPayment.getId(), true,
                    BigDecimal.valueOf(paidPayment.getAmount().getTotal()), paidPayment.getCurrency().getValue());
        }
        return new PortOnePayment(paymentId, false, null, null);
    }
}
