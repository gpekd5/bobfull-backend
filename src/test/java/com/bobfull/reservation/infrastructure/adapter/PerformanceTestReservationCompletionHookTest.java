package com.bobfull.reservation.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PerformanceTestReservationCompletionHookTest {

    private final PerformanceTestReservationCompletionHook hook = new PerformanceTestReservationCompletionHook();

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void 요청_컨텍스트가_없으면_지연없이_즉시_반환한다() {
        long start = System.nanoTime();
        hook.beforeCompletion(1L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(45L);
    }

    @Test
    void 헤더가_없으면_지연없이_즉시_반환한다() {
        bindHeaders(null, null);
        long start = System.nanoTime();
        hook.beforeCompletion(1L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(45L);
    }

    @Test
    void 지연_헤더만큼_대기한_뒤_반환한다() {
        bindHeaders(null, "50");
        long start = System.nanoTime();
        hook.beforeCompletion(1L);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(45L);
    }

    @Test
    void FAIL_결과_헤더는_지연없이_즉시_예외를_던진다() {
        bindHeaders("FAIL", "50");
        long start = System.nanoTime();
        assertThatThrownBy(() -> hook.beforeCompletion(1L)).isInstanceOf(IllegalStateException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(45L);
    }

    @Test
    void SUCCESS_결과_헤더는_예외없이_정상_반환한다() {
        bindHeaders("SUCCESS", null);
        hook.beforeCompletion(1L);
    }

    private void bindHeaders(String result, String delayMs) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (result != null) {
            request.addHeader("X-Perf-Reservation-Completion-Result", result);
        }
        if (delayMs != null) {
            request.addHeader("X-Perf-Reservation-Completion-Delay-Ms", delayMs);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
