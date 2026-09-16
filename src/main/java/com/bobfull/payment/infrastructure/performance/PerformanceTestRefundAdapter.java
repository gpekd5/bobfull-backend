package com.bobfull.payment.infrastructure.performance;

import com.bobfull.payment.application.port.PortOneRefundPort;
import com.bobfull.payment.infrastructure.portone.PortOneRefundGatewayAdapter;
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

// 성능 측정에서 요청 헤더로 PortOne 환불 결과와 지연을 재현한다.
// 실제 환불 요청을 건너뛰므로 performance 프로파일은 운영 환경에서 절대 활성화하면 안 된다.
@Component
@Profile("performance")
@Primary
public class PerformanceTestRefundAdapter implements PortOneRefundPort {

    private static final String HEADER_DELAY_MS = "X-Perf-Refund-Delay-Ms";
    private static final String HEADER_RESULT = "X-Perf-Refund-Result";

    // X-Perf-Refund-Delay-Ms와 X-Perf-Refund-Result로 지연과 결과를 결정하며 기본은 즉시 완료다.
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

    // 테스트 전용 설정이 배포 jar에 없으면 재조정 스케줄러가 실행될 수 있다.
    // 반복 예외로 로그를 오염시키지 않도록 재요청 없이 미완료 상태로 응답한다.
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
        // K6가 PROCESSING 응답 뒤 같은 ID로 완료 웹훅을 재현할 수 있도록 결정적으로 생성한다.
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
