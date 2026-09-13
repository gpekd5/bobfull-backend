package com.bobfull.payment.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundTest {
    private Payment payment() {
        return Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1, BigDecimal.TEN,
                Instant.parse("2026-07-30T00:10:00Z"));
    }

    @Test
    void idempotencyKey와_requestReason을_함께_저장한다() {
        Refund refund = Refund.create(payment(), BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "key-1", "cancel reason");

        assertThat(refund.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(refund.getRequestReason()).isEqualTo("cancel reason");
    }

    @Test
    void idempotencyKey가_null이거나_blank이면_생성할_수_없다() {
        assertThatThrownBy(() -> Refund.create(payment(), BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Refund.create(payment(), BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "  ", "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestReason이_null이거나_blank이면_생성할_수_없다() {
        assertThatThrownBy(() -> Refund.create(payment(), BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "key-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Refund.create(payment(), BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "key-1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
