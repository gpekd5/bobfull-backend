package com.bobfull.payment.application.port;

// 환불 생성 트랜잭션에서 외부 요청 식별자를 한 번만 발급하는 경계다.
public interface RefundIdempotencyKeyPort {
    String generate();
}
