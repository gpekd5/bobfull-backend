package com.bobfull.payment.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import com.bobfull.payment.application.service.RefundReconciliationProcessor;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.application.port.PortOneRefundPort;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationSchedulerTest {

    @Mock private RefundRepository refundRepository;
    @Mock private RefundReconciliationProcessor processor;
    @Mock private Refund first;
    @Mock private Refund second;

    @Test
    void 한_환불_조회가_실패해도_다음_후보를_계속_처리한다() {
        // given
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        given(refundRepository.findReconciliationCandidates(eq(List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING)),
                eq(now.minus(Duration.ofHours(24))), eq(now.minus(Duration.ofMinutes(10))),
                eq(now.minus(Duration.ofMinutes(5))), any(Pageable.class)))
                .willReturn(List.of(first, second));
        given(first.getUpdatedAt()).willReturn(now);
        given(second.getUpdatedAt()).willReturn(now);
        doThrow(new IllegalStateException("PortOne timeout")).when(processor).reconcile(first);
        given(processor.reconcile(second)).willReturn(PortOneRefundPort.ReconciliationResult.notCompleted());
        given(first.getId()).willReturn(1L);
        Payment firstPayment = org.mockito.Mockito.mock(Payment.class);
        given(first.getPayment()).willReturn(firstPayment);
        given(firstPayment.getPaymentId()).willReturn("payment-1");

        RefundReconciliationScheduler scheduler = new RefundReconciliationScheduler(refundRepository, processor,
                Clock.fixed(now, ZoneOffset.UTC), 20, Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofHours(24));

        // when
        scheduler.reconcileStalledRefunds();

        // then
        InOrder order = inOrder(processor);
        order.verify(processor).reconcile(first);
        order.verify(processor).reconcile(second);
    }
}
