package com.bobfull.reservation.infrastructure.performance;

import com.bobfull.reservation.application.port.ReservationCompletionTestPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// 요청 헤더로 Reservation 잠금 구간의 지연과 실패를 재현하는 성능 측정 전용 Adapter다.
// performance 프로파일에서만 등록되므로 운영 예약 완료 흐름에는 개입하지 않는다.
@Component
@Profile("performance")
public class HttpHeaderReservationCompletionTestAdapter implements ReservationCompletionTestPort {

    private static final String HEADER_RESULT = "X-Perf-Reservation-Completion-Result";
    private static final String HEADER_DELAY_MS = "X-Perf-Reservation-Completion-Delay-Ms";

    @Override
    public void beforeCompletion(Long reservationId) {
        String result = readHeader(HEADER_RESULT);
        // 참여자 조건부 UPDATE 전에 실패시켜 결제 완료 트랜잭션 전체 롤백을 측정한다.
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
