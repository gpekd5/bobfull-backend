package com.bobfull.common.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 운영 대시보드용 비즈니스 사건 Counter를 핵심 흐름과 격리해 기록한다.
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
        // 관측 시스템 실패가 비즈니스 트랜잭션을 실패시키지 않도록 기록 오류를 격리한다.
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
