package com.bobfull.payment.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentExpirationTest {
    private Payment ready(Instant expiresAt) {
        return Payment.createReady("payment-id", 1L, 2L, null, PaymentPurpose.CREATE, 1, BigDecimal.TEN, expiresAt);
    }

    @Test
    void 만료_시각과_같거나_지난_READY만_EXPIRED로_전이한다() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        Payment elapsed = ready(now.minusSeconds(1));
        Payment boundary = ready(now);
        Payment future = ready(now.plusSeconds(1));
        assertThat(elapsed.expireIfNeeded(now)).isTrue();
        assertThat(boundary.expireIfNeeded(now)).isTrue();
        assertThat(future.expireIfNeeded(now)).isFalse();
        assertThat(elapsed.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(boundary.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(future.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    void 이미_완료되었거나_만료된_Payment은_변경하지_않는다() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        Payment paid = ready(now.minusSeconds(1)); paid.complete(now.minusSeconds(2));
        Payment expired = ready(now.minusSeconds(1)); expired.expireIfNeeded(now);
        assertThat(paid.expireIfNeeded(now)).isFalse();
        assertThat(expired.expireIfNeeded(now)).isFalse();
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(expired.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void 결제전체환불이_완료되면_paidAt을_보존하고_REFUNDED로_전이한다() {
        Instant paidAt = Instant.parse("2026-07-30T00:00:00Z");
        Payment paid = ready(paidAt.plusSeconds(600));
        paid.complete(paidAt);

        paid.markRefunded();

        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paid.getPaidAt()).isEqualTo(paidAt);
    }
}
