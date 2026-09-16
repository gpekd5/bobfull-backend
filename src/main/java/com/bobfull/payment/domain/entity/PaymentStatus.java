package com.bobfull.payment.domain.entity;

// 내부 결제의 준비·완료·실패·만료·환불 상태를 표현한다.
public enum PaymentStatus {

    READY,
    PAID,
    EXPIRED,
    FAILED,
    REFUNDED
}
