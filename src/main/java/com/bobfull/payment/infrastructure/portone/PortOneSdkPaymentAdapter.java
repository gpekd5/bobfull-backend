package com.bobfull.payment.infrastructure.portone;

import com.bobfull.payment.application.port.PortOnePaymentPort;
import io.portone.sdk.server.PortOneClient;
import io.portone.sdk.server.payment.PaidPayment;
import io.portone.sdk.server.payment.Payment;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** PortOne SDK 타입을 결제 도메인 내부 모델로 변환한다. */
@Component
@RequiredArgsConstructor
public class PortOneSdkPaymentAdapter implements PortOnePaymentPort {
    private final PortOneClient portOneClient;

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
