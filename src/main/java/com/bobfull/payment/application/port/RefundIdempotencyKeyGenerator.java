package com.bobfull.payment.application.port;

/** Refund 생성 트랜잭션에서 외부 요청 식별자를 한 번만 발급한다. */
public interface RefundIdempotencyKeyGenerator {
    String generate();
}
