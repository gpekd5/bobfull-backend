package com.bobfull.payment.infrastructure.adapter;

import com.bobfull.payment.application.port.PortOneRefundRequester;
import com.bobfull.payment.infrastructure.scheduler.RefundReconciliationScheduler;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Issue #146 K6 성능 측정 전용 대체 구현이다. 실제 PortOne 환불 요청 API를 호출하지 않고,
 * K6 시나리오가 보낸 요청 헤더로 결과·지연을 제어해 즉시 응답 경로(시나리오 A/C/D/E/F)를
 * 외부 네트워크 없이 재현한다. {@code performance} 프로파일에서만 활성화되며, 다른
 * 프로파일에서는 {@link PortOneRefundGatewayAdapter}가 그대로 쓰인다.
 *
 * <p>제어 헤더(모두 생략 가능, 생략 시 지연 없이 즉시 완료):</p>
 * <ul>
 *   <li>{@code X-Perf-Refund-Delay-Ms}: 응답 전 대기할 시간(ms)</li>
 *   <li>{@code X-Perf-Refund-Result}: {@code SUCCESS}(기본) | {@code PROCESSING} | {@code FAILURE} |
 *       {@code TIMEOUT} | {@code CONNECTION_RESET}</li>
 * </ul>
 *
 * <p>cancellationId는 {@code paymentId}에서 결정적으로 생성한다(무작위 값이 아니다) — 시나리오
 * B/C처럼 즉시 응답을 {@code PROCESSING}으로 받은 뒤 K6가 같은 cancellationId로 CANCELLED 웹훅을
 * 직접 서명해 보내 완료를 재현해야 할 때, 응답 본문에서 별도로 값을 꺼내지 않고도 재계산할 수 있게 한다.</p>
 *
 * <p><b>운영 주의:</b> 이 Bean은 실제 PortOne 환불 요청을 완전히 건너뛰고 요청 헤더만 보고
 * 응답한다 — {@code performance}가 운영 배포의 {@code SPRING_PROFILES_ACTIVE}에 절대 섞이지
 * 않아야 한다(환불 요청 자체가 무력화된다).</p>
 */
@Component
@Profile("performance")
@Primary
public class PerformanceTestRefundRequester implements PortOneRefundRequester {

    private static final String HEADER_DELAY_MS = "X-Perf-Refund-Delay-Ms";
    private static final String HEADER_RESULT = "X-Perf-Refund-Result";

    @Override
    public RefundResult request(String paymentId, BigDecimal amount, String reason, String idempotencyKey) {
        applyDelay(readHeader(HEADER_DELAY_MS));
        String result = readHeader(HEADER_RESULT);
        String cancellationId = cancellationIdFor(paymentId);
        if (result == null || result.isBlank() || "SUCCESS".equalsIgnoreCase(result)) {
            return new RefundResult(cancellationId, true);
        }
        if ("PROCESSING".equalsIgnoreCase(result)) {
            return new RefundResult(cancellationId, false);
        }
        if ("FAILURE".equalsIgnoreCase(result)) {
            throw new ExplicitRefundFailureException("performance profile forced failure");
        }
        if ("TIMEOUT".equalsIgnoreCase(result)) {
            throw new CompletionException(new TimeoutException("performance profile forced timeout"));
        }
        if ("CONNECTION_RESET".equalsIgnoreCase(result)) {
            throw new CompletionException(new IOException("performance profile forced connection reset"));
        }
        throw new IllegalArgumentException("Unknown " + HEADER_RESULT + " value: " + result);
    }

    @Override
    public boolean isCancellationCompleted(String paymentId, String cancellationId) {
        return true;
    }

    /**
     * {@code application-performance.yml}(테스트 클래스패스 전용)은 배포 가능한 jar에 포함되지
     * 않아, 실제 배포 환경에서는 {@code payment.refund-reconciliation.enabled=false}가 적용되지
     * 않을 수 있다 — 즉 {@link RefundReconciliationScheduler}가 여전히 동작해 이 Bean의
     * {@code reconcile()}을 호출할 수 있다. 기본 구현(UnsupportedOperationException)을 그대로
     * 두면 그때마다 예외가 반복돼 로그가 오염되므로, "아직 완료되지 않음"으로 안전하게 응답한다.
     */
    @Override
    public ReconciliationResult reconcile(String paymentId, String cancellationId, BigDecimal refundAmount,
            Instant refundRequestedAt) {
        return ReconciliationResult.notCompleted();
    }

    private void applyDelay(String delayMsHeader) {
        if (delayMsHeader == null || delayMsHeader.isBlank()) {
            return;
        }
        long delayMs = Long.parseLong(delayMsHeader.trim());
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String cancellationIdFor(String paymentId) {
        return "perf-cancel-" + paymentId;
    }

    private String readHeader(String name) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        return servletAttributes.getRequest().getHeader(name);
    }
}
