package com.bobfull.payment.application.port;

import java.math.BigDecimal;

// 결제 완료 전에 PortOne의 결제 상태와 금액·통화를 조회하는 외부 경계다.
public interface PortOnePaymentPort {

    PortOnePayment read(String paymentId);

    record PortOnePayment(String paymentId, boolean paid, BigDecimal amount, String currency) {
    }
}
