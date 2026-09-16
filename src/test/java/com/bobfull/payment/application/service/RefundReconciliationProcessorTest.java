package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.application.port.PortOneRefundPort;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationProcessorTest {

    @Mock private PortOneRefundPort refundPort;
    @Mock private RefundTransactionService transactionService;
    @Mock private RefundCompletionService completionService;
    @Mock private Refund refund;
    @Mock private Payment payment;

    private RefundReconciliationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RefundReconciliationProcessor(refundPort, transactionService, completionService);
        given(refund.getId()).willReturn(1L);
        given(refund.getPayment()).willReturn(payment);
        given(payment.getAmount()).willReturn(BigDecimal.TEN);
        given(refund.getAmount()).willReturn(BigDecimal.TEN);
        // Payment.amount 불일치로 조회 전에 반환되는 테스트는 아래 두 stub을 쓰지 않으므로 lenient로 둔다.
        org.mockito.Mockito.lenient().when(payment.getPaymentId()).thenReturn("payment-1");
        org.mockito.Mockito.lenient().when(refund.getRequestedAt()).thenReturn(Instant.parse("2026-08-05T00:00:00Z"));
    }

    @Test
    void COMPLETED이면_완료_경로를_호출하고_PG_조회_시각을_기록한다() {
        given(refund.getCancellationId()).willReturn(null);
        given(refundPort.reconcile("payment-1", null, BigDecimal.TEN, Instant.parse("2026-08-05T00:00:00Z")))
                .willReturn(PortOneRefundPort.ReconciliationResult.completed("cancel-1",
                        Instant.parse("2026-08-05T00:01:00Z")));

        var result = processor.reconcile(refund);

        assertThat(result.status()).isEqualTo(PortOneRefundPort.ReconciliationStatus.COMPLETED);
        verify(completionService).reflectExternalResult(1L, "cancel-1", true);
        verify(transactionService).markPgChecked(1L);
    }

    @Test
    void PROCESSING이고_cancellationId가_있으면_완료_경로를_미완료로_호출한다() {
        given(refund.getCancellationId()).willReturn("cancel-1");
        given(refundPort.reconcile("payment-1", "cancel-1", BigDecimal.TEN, Instant.parse("2026-08-05T00:00:00Z")))
                .willReturn(PortOneRefundPort.ReconciliationResult.processing("cancel-1"));

        processor.reconcile(refund);

        verify(completionService).reflectExternalResult(1L, "cancel-1", false);
        verify(transactionService).markPgChecked(1L);
    }

    @Test
    void NOT_COMPLETED이면_완료_경로를_호출하지_않는다() {
        given(refund.getCancellationId()).willReturn(null);
        given(refundPort.reconcile(eq("payment-1"), eq((String) null), eq(BigDecimal.TEN),
                eq(Instant.parse("2026-08-05T00:00:00Z"))))
                .willReturn(PortOneRefundPort.ReconciliationResult.notCompleted());

        processor.reconcile(refund);

        verify(completionService, never()).reflectExternalResult(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(transactionService).markPgChecked(1L);
    }

    @Test
    void AMBIGUOUS이면_완료_경로를_호출하지_않지만_PG_조회_시각은_기록한다() {
        given(refund.getCancellationId()).willReturn(null);
        given(refundPort.reconcile(eq("payment-1"), eq((String) null), eq(BigDecimal.TEN),
                eq(Instant.parse("2026-08-05T00:00:00Z"))))
                .willReturn(PortOneRefundPort.ReconciliationResult.ambiguous("multiple or mixed cancellations"));

        processor.reconcile(refund);

        verify(completionService, never()).reflectExternalResult(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(transactionService).markPgChecked(1L);
    }

    @Test
    void 조회_자체가_실패해도_PG_조회_시각은_기록하고_예외는_전파한다() {
        given(refund.getCancellationId()).willReturn(null);
        given(refundPort.reconcile(eq("payment-1"), eq((String) null), eq(BigDecimal.TEN),
                eq(Instant.parse("2026-08-05T00:00:00Z"))))
                .willThrow(new IllegalStateException("PortOne timeout"));

        assertThatThrownBy(() -> processor.reconcile(refund))
                .isInstanceOf(RefundReconciliationProcessor.RefundLookupException.class);

        verify(transactionService).markPgChecked(1L);
        verify(completionService, never()).reflectExternalResult(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** #148 재검토 반영: Issue #141 확정 계약은 PaymentCancellation.totalAmount가 Refund.amount와
     * Payment.amount에 모두 일치해야 완료로 인정한다고 명시한다. Refund.amount와 Payment.amount가
     * 어긋난 비정상 데이터에서는 외부 조회 자체를 시도하지 않고 AMBIGUOUS로 즉시 상태를 유지해야
     * 한다(MAJOR 리뷰 반영 항목의 회귀 방지). */
    @Test
    void Refund금액과_Payment금액이_다르면_외부조회없이_AMBIGUOUS를_반환한다() {
        given(payment.getAmount()).willReturn(BigDecimal.valueOf(9000));

        var result = processor.reconcile(refund);

        assertThat(result.status()).isEqualTo(PortOneRefundPort.ReconciliationStatus.AMBIGUOUS);
        verify(refundPort, never()).reconcile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(completionService, never()).reflectExternalResult(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(transactionService).markPgChecked(1L);
    }

    /** #148 재검토 반영: 조회 실패(RefundLookupException)와 markPgChecked 실패가 겹치면, 기존
     * try/finally 구조는 finally의 예외가 try의 예외를 완전히 덮어써 조회 실패 원인이 사라졌다.
     * 먼저 발생한 실패(조회 실패)가 그대로 전파되어야 한다. */
    @Test
    void 조회실패와_PG조회시각_기록실패가_겹치면_먼저_발생한_조회실패가_전파된다() {
        given(refund.getCancellationId()).willReturn(null);
        given(refundPort.reconcile(eq("payment-1"), eq((String) null), eq(BigDecimal.TEN),
                eq(Instant.parse("2026-08-05T00:00:00Z"))))
                .willThrow(new IllegalStateException("PortOne timeout"));
        org.mockito.Mockito.doThrow(new RuntimeException("DB unavailable")).when(transactionService).markPgChecked(1L);

        assertThatThrownBy(() -> processor.reconcile(refund))
                .isInstanceOf(RefundReconciliationProcessor.RefundLookupException.class);
    }
}
