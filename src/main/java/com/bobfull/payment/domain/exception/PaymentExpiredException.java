package com.bobfull.payment.domain.exception;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.entity.PaymentStatus;
import java.time.Instant;

public class PaymentExpiredException extends CustomException {

    private final PaymentStatus internalStatus;
    private final Instant expiresAt;

    public PaymentExpiredException(PaymentStatus internalStatus, Instant expiresAt) {
        super(PaymentErrorCode.PAYMENT_EXPIRED);
        this.internalStatus = internalStatus;
        this.expiresAt = expiresAt;
    }

    public PaymentStatus getInternalStatus() {
        return internalStatus;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
