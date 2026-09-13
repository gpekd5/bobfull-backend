package com.bobfull.payment.domain.entity;

/**
 * Payment의 결제 처리 상태다.
 */
public enum PaymentStatus {

    READY,
    PAID,
    EXPIRED,
    FAILED,
    REFUNDED
}
