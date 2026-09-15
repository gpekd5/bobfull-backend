package com.bobfull.payment.infrastructure.idempotency;

import com.bobfull.payment.application.port.RefundIdempotencyKeyPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidRefundIdempotencyKeyAdapter implements RefundIdempotencyKeyPort {
    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
