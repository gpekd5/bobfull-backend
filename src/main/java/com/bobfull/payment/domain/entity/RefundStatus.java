package com.bobfull.payment.domain.entity;

// 외부 환불 요청의 접수·처리·완료·실패 상태를 표현한다.
public enum RefundStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED
}
