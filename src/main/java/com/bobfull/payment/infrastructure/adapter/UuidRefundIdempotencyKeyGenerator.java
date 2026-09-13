package com.bobfull.payment.infrastructure.adapter;

import com.bobfull.payment.application.port.RefundIdempotencyKeyGenerator;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidRefundIdempotencyKeyGenerator implements RefundIdempotencyKeyGenerator {
    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
