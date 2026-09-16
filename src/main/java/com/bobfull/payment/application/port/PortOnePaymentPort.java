package com.bobfull.payment.application.port;

import java.math.BigDecimal;

public interface PortOnePaymentPort {

    PortOnePayment read(String paymentId);

    record PortOnePayment(String paymentId, boolean paid, BigDecimal amount, String currency) {
    }
}
