package com.bobfull.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.config.JpaAuditingConfig;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

/** findReconciliationCandidates는 @LastModifiedDate(updatedAt) 기준으로 필터링하므로,
 * @DataJpaTest 슬라이스에는 기본 포함되지 않는 Auditing 설정을 명시적으로 가져온다. */
@DataJpaTest
@Import({JpaAuditingConfig.class, ClockConfig.class})
class RefundRepositoryTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;

    @Test
    void 환불은_결제와_연결해_본인소유조건으로_조회한다() {
        // given
        Payment payment = paymentRepository.saveAndFlush(payment("payment-id", 1L));
        Refund refund = refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.TEN, RefundStatus.COMPLETED,
                Instant.parse("2026-07-30T00:00:00Z"), Instant.parse("2026-07-30T00:01:00Z"),
                "test-key-owner-lookup", "test reason"));

        // when & then
        assertThat(refundRepository.findByIdAndPayment_MemberId(refund.getId(), 1L)).isPresent();
        assertThat(refundRepository.findByIdAndPayment_MemberId(refund.getId(), 2L)).isEmpty();
    }

    @Test
    void 하나의_결제에는_환불을_하나만_저장한다() {
        // given
        Payment payment = paymentRepository.saveAndFlush(payment("payment-id", 1L));
        refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "test-key-single-refund-1", "test reason"));

        // when & then
        assertThatThrownBy(() -> refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:01Z"), null, "test-key-single-refund-2", "test reason")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 서로_다른_결제여도_동일한_idempotencyKey는_저장할_수_없다() {
        // given
        Payment first = paymentRepository.saveAndFlush(payment("payment-first", 1L));
        Payment second = paymentRepository.saveAndFlush(payment("payment-second", 1L));
        refundRepository.saveAndFlush(Refund.create(first, BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "dup-key", "first reason"));

        // when & then
        assertThatThrownBy(() -> refundRepository.saveAndFlush(Refund.create(second, BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:01Z"), null, "dup-key", "second reason")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 지정한_상태의_환불만_재확인_후보로_조회한다() {
        // given
        Payment requestedPayment = paymentRepository.saveAndFlush(payment("payment-requested", 1L));
        Payment processingPayment = paymentRepository.saveAndFlush(payment("payment-processing", 1L));
        Payment completedPayment = paymentRepository.saveAndFlush(payment("payment-completed", 1L));
        Refund requested = refundRepository.saveAndFlush(Refund.create(requestedPayment, BigDecimal.TEN,
                RefundStatus.REQUESTED, Instant.parse("2026-07-30T00:00:00Z"), null,
                "test-key-candidates-requested", "test reason"));
        refundRepository.saveAndFlush(Refund.create(processingPayment, BigDecimal.TEN,
                RefundStatus.PROCESSING, Instant.parse("2026-07-30T00:00:00Z"), null,
                "test-key-candidates-processing", "test reason"));
        refundRepository.saveAndFlush(Refund.create(completedPayment, BigDecimal.TEN,
                RefundStatus.COMPLETED, Instant.parse("2026-07-30T00:00:00Z"), Instant.parse("2026-07-30T00:01:00Z"),
                "test-key-candidates-completed", "test reason"));
        Instant now = Instant.now();

        // when
        List<Refund> candidates = refundRepository.findReconciliationCandidates(
                List.of(RefundStatus.REQUESTED), now.minusSeconds(3600), now.plusSeconds(3600), now.plusSeconds(3600),
                PageRequest.of(0, 20));

        // then
        assertThat(candidates).extracting(Refund::getId).containsExactly(requested.getId());
    }

    @Test
    void 경과시간_기준에_아직_미달한_환불은_후보에서_제외한다() {
        // given
        Payment payment = paymentRepository.saveAndFlush(payment("payment-fresh", 1L));
        refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "test-key-fresh", "test reason"));
        Instant now = Instant.now();

        // when
        List<Refund> candidates = refundRepository.findReconciliationCandidates(
                List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING),
                now.minusSeconds(7200), now.minusSeconds(3600), now.plusSeconds(3600), PageRequest.of(0, 20));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void max_age보다_오래된_환불은_더_이상_후보로_재시도하지_않는다() {
        // given: 재조정 스케줄러가 24시간 넘게 재시도해도 계속 실패하는 환불(Issue #272) —
        // 자원 낭비를 막기 위해 이 시점부터는 후보에서 완전히 제외돼야 한다. updatedAt은
        // @LastModifiedDate가 저장 시점(현재)으로 채우므로, updatedAfter를 미래로 둬 "이미
        // max-age 기준보다 오래된 상태"를 흉내낸다(다른 테스트의 미래 기준값 방식과 동일).
        Payment payment = paymentRepository.saveAndFlush(payment("payment-permanently-stuck", 1L));
        refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "test-key-permanently-stuck", "test reason"));
        Instant now = Instant.now();

        // when
        List<Refund> candidates = refundRepository.findReconciliationCandidates(
                List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING),
                now.plusSeconds(3600), now.plusSeconds(7200), now.plusSeconds(7200),
                PageRequest.of(0, 20));

        // then
        assertThat(candidates).isEmpty();
    }

    @Test
    void 최근에_PG_조회를_마친_환불은_재확인_지연시간이_지나기_전까지_후보에서_제외한다() {
        // given
        Payment recentlyCheckedPayment = paymentRepository.saveAndFlush(payment("payment-recently-checked", 1L));
        Payment neverCheckedPayment = paymentRepository.saveAndFlush(payment("payment-never-checked", 1L));
        Refund recentlyChecked = refundRepository.saveAndFlush(Refund.create(recentlyCheckedPayment, BigDecimal.TEN,
                RefundStatus.REQUESTED, Instant.parse("2026-07-30T00:00:00Z"), null,
                "test-key-recently-checked", "test reason"));
        Instant now = Instant.now();
        recentlyChecked.markPgChecked(now);
        refundRepository.saveAndFlush(recentlyChecked);
        Refund neverChecked = refundRepository.saveAndFlush(Refund.create(neverCheckedPayment, BigDecimal.TEN,
                RefundStatus.REQUESTED, Instant.parse("2026-07-30T00:00:00Z"), null,
                "test-key-never-checked", "test reason"));

        // when
        List<Refund> candidates = refundRepository.findReconciliationCandidates(
                List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING),
                now.minusSeconds(3600), now.plusSeconds(3600), now.minusSeconds(60), PageRequest.of(0, 20));

        // then
        assertThat(candidates).extracting(Refund::getId).containsExactly(neverChecked.getId());
    }

    @Test
    void 조회시각_오름차순으로_정렬해_배치개수만큼만_조회한다() {
        // given
        Payment oldestPayment = paymentRepository.saveAndFlush(payment("payment-oldest", 1L));
        Payment middlePayment = paymentRepository.saveAndFlush(payment("payment-middle", 1L));
        Payment newestPayment = paymentRepository.saveAndFlush(payment("payment-newest", 1L));
        Refund oldest = refundRepository.saveAndFlush(Refund.create(oldestPayment, BigDecimal.TEN,
                RefundStatus.REQUESTED, Instant.parse("2026-07-30T00:00:00Z"), null,
                "test-key-oldest", "test reason"));
        Refund middle = refundRepository.saveAndFlush(Refund.create(middlePayment, BigDecimal.TEN,
                RefundStatus.REQUESTED, Instant.parse("2026-07-30T00:00:00Z"), null,
                "test-key-middle", "test reason"));
        Refund newest = refundRepository.saveAndFlush(Refund.create(newestPayment, BigDecimal.TEN,
                RefundStatus.REQUESTED, Instant.parse("2026-07-30T00:00:00Z"), null,
                "test-key-newest", "test reason"));
        Instant now = Instant.now();
        oldest.markPgChecked(now.minus(java.time.Duration.ofMinutes(30)));
        middle.markPgChecked(now.minus(java.time.Duration.ofMinutes(20)));
        newest.markPgChecked(now.minus(java.time.Duration.ofMinutes(10)));
        refundRepository.saveAndFlush(oldest);
        refundRepository.saveAndFlush(middle);
        refundRepository.saveAndFlush(newest);

        // when
        List<Refund> candidates = refundRepository.findReconciliationCandidates(
                List.of(RefundStatus.REQUESTED), now.minusSeconds(3600), now.plusSeconds(3600), now,
                PageRequest.of(0, 2));

        // then
        assertThat(candidates).extracting(Refund::getId).containsExactly(oldest.getId(), middle.getId());
    }

    private Payment payment(String paymentId, Long memberId) {
        return Payment.createReady(paymentId, memberId, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.TEN, Instant.parse("2026-07-30T00:10:00Z"));
    }
}
