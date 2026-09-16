package com.bobfull.common.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 운영 대시보드용 비즈니스 Counter를 기록한다.
 * 메트릭 기록 실패가 핵심 트랜잭션 흐름에 영향을 주지 않도록 내부에서 예외를 삼킨다.
 */
@Slf4j
@Component
public class BusinessMetricRecorder {

    public static final String METRIC_NAME = "bobfull_business_events";

    private final MeterRegistry meterRegistry;
    private final Map<BusinessMetricEvent, Counter> counters = new ConcurrentHashMap<>();

    public BusinessMetricRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        prewarmCounters();
    }

    public void increment(BusinessMetricEvent event) {
        try {
            counterFor(event).increment();
        } catch (RuntimeException exception) {
            log.warn("businessMetricRecordFailed event={} reason={}", event.name(),
                    exception.getClass().getSimpleName());
        }
    }

    private void prewarmCounters() {
        for (BusinessMetricEvent event : BusinessMetricEvent.values()) {
            try {
                counterFor(event);
            } catch (RuntimeException exception) {
                log.warn("businessMetricPrewarmFailed event={} reason={}", event.name(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private Counter counterFor(BusinessMetricEvent event) {
        return counters.computeIfAbsent(event, this::registerCounter);
    }

    private Counter registerCounter(BusinessMetricEvent event) {
        return Counter.builder(METRIC_NAME)
                .description("BobFull business event occurrences")
                .tag("event", event.name())
                .register(meterRegistry);
    }
}
