package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.config.JpaAuditingConfig;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import com.bobfull.payment.infrastructure.idempotency.UuidRefundIdempotencyKeyAdapter;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * markPgChecked()는 REQUIRES_NEW 독립 트랜잭션에서 lastPgCheckedAt만 갱신해야 한다.
 * 엔티티를 통해 갱신하면 BaseTimeEntity의 @LastModifiedDate(updatedAt)까지 함께 갱신되어
 * 재확인 후보 순환과 30·60분 장기 미완료 경보가 무력화되는 BLOCKER가 있었다(#148 팀원 리뷰).
 * 테스트가 REQUIRES_NEW 트랜잭션의 커밋을 실제로 관찰하려면, @DataJpaTest 기본 트랜잭션
 * 래핑을 꺼야 한다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, ClockConfig.class, RefundTransactionService.class, UuidRefundIdempotencyKeyAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundTransactionServiceTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private RefundTransactionService transactionService;
    @Autowired private EntityManager entityManager;
    @MockitoBean private BusinessMetricRecorder businessMetricRecorder;

    @Test
    void markPgChecked은_updatedAt은_보존한_채_lastPgCheckedAt만_갱신한다() throws InterruptedException {
        // given
        Payment payment = paymentRepository.saveAndFlush(payment("payment-mark-checked"));
        Refund refund = refundRepository.saveAndFlush(Refund.create(payment, BigDecimal.TEN, RefundStatus.REQUESTED,
                Instant.parse("2026-07-30T00:00:00Z"), null, "test-key-mark-pg-checked", "test reason"));
        Long refundId = refund.getId();
        entityManager.clear();
        Instant updatedAtBefore = refundRepository.findById(refundId).orElseThrow().getUpdatedAt();
        Thread.sleep(1100); // updatedAt이 초 단위로도 흔들리면 잡히도록 충분한 간격을 둔다.

        // when
        transactionService.markPgChecked(refundId);

        // then
        entityManager.clear();
        Refund reloaded = refundRepository.findById(refundId).orElseThrow();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAtBefore);
        assertThat(reloaded.getLastPgCheckedAt()).isNotNull().isAfter(updatedAtBefore);

        refundRepository.delete(reloaded);
        paymentRepository.delete(payment);
    }

    @Test
    void 존재하지_않는_환불이면_예외를_던진다() {
        assertThatThrownBy(() -> transactionService.markPgChecked(999_999L))
                .isInstanceOf(CustomException.class);
    }

    private Payment payment(String paymentId) {
        return Payment.createReady(paymentId, 1L, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.TEN, Instant.parse("2026-07-30T00:10:00Z"));
    }
}
