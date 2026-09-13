package com.bobfull.payment.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.payment.application.port.PortOneRefundRequester;
import com.bobfull.payment.application.port.PortOneRefundRequester.RefundResult;
import java.math.BigDecimal;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PerformanceTestRefundRequesterTest {

    private final PerformanceTestRefundRequester requester = new PerformanceTestRefundRequester();

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void 요청_컨텍스트가_없으면_지연없이_즉시_성공한다() {
        RefundResult result = requester.request("payment-1", BigDecimal.TEN, "reason", "key");
        assertThat(result.completed()).isTrue();
        assertThat(result.cancellationId()).contains("payment-1");
    }

    @Test
    void PROCESSING_헤더는_완료되지_않은_결과를_반환한다() {
        bindHeader("X-Perf-Refund-Result", "PROCESSING");
        RefundResult result = requester.request("payment-1", BigDecimal.TEN, "reason", "key");
        assertThat(result.completed()).isFalse();
    }

    @Test
    void FAILURE_헤더는_명시적_실패_예외를_던진다() {
        bindHeader("X-Perf-Refund-Result", "FAILURE");
        assertThatThrownBy(() -> requester.request("payment-1", BigDecimal.TEN, "reason", "key"))
                .isInstanceOf(PortOneRefundRequester.ExplicitRefundFailureException.class);
    }

    @Test
    void TIMEOUT_헤더는_TimeoutException을_감싼_CompletionException을_던진다() {
        bindHeader("X-Perf-Refund-Result", "TIMEOUT");
        assertThatThrownBy(() -> requester.request("payment-1", BigDecimal.TEN, "reason", "key"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void CONNECTION_RESET_헤더는_IOException을_감싼_CompletionException을_던진다() {
        bindHeader("X-Perf-Refund-Result", "CONNECTION_RESET");
        assertThatThrownBy(() -> requester.request("payment-1", BigDecimal.TEN, "reason", "key"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void 알수없는_결과_헤더는_즉시_실패한다() {
        bindHeader("X-Perf-Refund-Result", "NOT_A_REAL_VALUE");
        assertThatThrownBy(() -> requester.request("payment-1", BigDecimal.TEN, "reason", "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 지연_헤더만큼_대기한_뒤_응답한다() {
        bindHeader("X-Perf-Refund-Delay-Ms", "50");
        long start = System.nanoTime();
        requester.request("payment-1", BigDecimal.TEN, "reason", "key");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(45L);
    }

    @Test
    void isCancellationCompleted은_항상_true다() {
        assertThat(requester.isCancellationCompleted("payment-1", "cancel-1")).isTrue();
    }

    @Test
    void reconcile은_예외없이_완료되지않음으로_응답한다() {
        var result = requester.reconcile("payment-1", "cancel-1", BigDecimal.TEN, java.time.Instant.now());
        assertThat(result.status()).isEqualTo(PortOneRefundRequester.ReconciliationStatus.NOT_COMPLETED);
    }

    private void bindHeader(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(name, value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
