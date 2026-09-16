package com.bobfull.reservation.infrastructure.performance;

import com.bobfull.reservation.application.port.ReservationCompletionTestPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Issue #146 K6 성능 측정 전용 구현이다. 요청 헤더로 예약 완료(Reservation 락을 쥔 구간)에
 * 지연을 넣거나(시나리오 D) 실패를 강제한다(시나리오 E). {@code performance} 프로파일에서만
 * Bean으로 등록되며, 다른 프로파일에서는 Bean이 없어 {@link
 * com.bobfull.reservation.application.service.ReservationCancellationCompletionService}가 아무 영향도
 * 받지 않는다(운영 코드에 영구 반영되는 지연·실패가 아니다).
 *
 * <p>제어 헤더(모두 생략 가능, 생략 시 지연·실패 없음):</p>
 * <ul>
 *   <li>{@code X-Perf-Reservation-Completion-Result}: {@code SUCCESS}(기본) | {@code FAIL} —
 *       FAIL이면 참여자 조건부 UPDATE 전에 예외를 던져, 예약 완료 처리가 실패했을 때 결제 완료
 *       트랜잭션 전체가 롤백되는 경로(Issue #146 시나리오 E)를 재현한다.</li>
 *   <li>{@code X-Perf-Reservation-Completion-Delay-Ms}: 실패시키지 않을 때 대기할 시간(ms).</li>
 * </ul>
 */
@Component
@Profile("performance")
public class HttpHeaderReservationCompletionTestAdapter implements ReservationCompletionTestPort {

    private static final String HEADER_RESULT = "X-Perf-Reservation-Completion-Result";
    private static final String HEADER_DELAY_MS = "X-Perf-Reservation-Completion-Delay-Ms";

    @Override
    public void beforeCompletion(Long reservationId) {
        String result = readHeader(HEADER_RESULT);
        if ("FAIL".equalsIgnoreCase(result)) {
            throw new IllegalStateException(
                    "performance profile forced reservation completion failure: reservationId=" + reservationId);
        }
        applyDelay(readHeader(HEADER_DELAY_MS));
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

    private String readHeader(String name) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        return servletAttributes.getRequest().getHeader(name);
    }
}
