package com.bobfull.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
class PaymentExpirationRepositoryTest {

    @Autowired private PaymentRepository repository;

    @Test
    void READY이고_cutoff이하인_후보만_만료시각과_내부PK_오름차순으로_batch만큼_반환한다() {
        Instant cutoff = Instant.parse("2026-07-30T00:00:00Z");
        Payment earliest = save("earliest", cutoff.minusSeconds(1), PaymentStatus.READY);
        Payment sameTimeFirst = save("same-first", cutoff, PaymentStatus.READY);
        Payment sameTimeSecond = save("same-second", cutoff, PaymentStatus.READY);
        Payment futureReady = save("future", cutoff.plusSeconds(1), PaymentStatus.READY);
        Payment paid = save("paid", cutoff, PaymentStatus.PAID);
        Payment failed = save("failed", cutoff, PaymentStatus.FAILED);
        Payment refunded = save("refunded", cutoff, PaymentStatus.REFUNDED);
        Payment expired = save("expired", cutoff, PaymentStatus.EXPIRED);

        List<Long> firstBatch = repository.findExpirationCandidateIds(PaymentStatus.READY, cutoff, PageRequest.of(0, 2));
        List<Long> allCandidates = repository.findExpirationCandidateIds(PaymentStatus.READY, cutoff, PageRequest.of(0, 100));

        assertThat(firstBatch).containsExactly(earliest.getId(), sameTimeFirst.getId());
        assertThat(allCandidates).containsExactly(earliest.getId(), sameTimeFirst.getId(), sameTimeSecond.getId());
        assertThat(allCandidates).doesNotContain(futureReady.getId(), paid.getId(), failed.getId(), refunded.getId(), expired.getId());
        assertThat(allCandidates).allMatch(id -> id instanceof Long);
    }

    @Test
    void 만료조회용_복합인덱스가_Entity_계약에_선언된다() {
        Table table = Payment.class.getAnnotation(Table.class);

        assertThat(table.indexes()).extracting(Index::columnList)
                .contains("payment_status, expires_at, payment_id");
    }

    private Payment save(String paymentId, Instant expiresAt, PaymentStatus status) {
        Payment payment = Payment.createReady(paymentId, 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.TEN, expiresAt);
        ReflectionTestUtils.setField(payment, "status", status);
        return repository.saveAndFlush(payment);
    }
}
