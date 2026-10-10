package com.bobfull.chat.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModerationMetrics {

    private final MeterRegistry meterRegistry;

    public void recordRoute(String route, String promptVersion, String policyVersion) {
        increment("bobfull.ai.moderation.route", "route", route, promptVersion, policyVersion);
    }

    public void recordFinalStatus(String status, String promptVersion, String policyVersion) {
        increment("bobfull.ai.moderation.final.status", "status", status, promptVersion, policyVersion);
    }

    public void recordFailure(String stage, String promptVersion, String policyVersion) {
        increment("bobfull.ai.moderation.failure", "stage", stage, promptVersion, policyVersion);
    }

    public void recordKafkaRetry() {
        try {
            Counter.builder("bobfull.ai.moderation.kafka.retry")
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ex) {
            log.warn("Failed to record moderation metric. metric={}",
                    "bobfull.ai.moderation.kafka.retry",
                    ex);
        }
    }

    private void increment (String metricName, String tagName, String tagValue, String promptVersion, String policyVersion) {
        try {
            Counter.builder(metricName)
                    .tag(tagName, tagValue)
                    .tag("prompt_version", promptVersion)
                    .tag("policy_version", policyVersion)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to record moderation metric. metricName={}, tagName={}, tagValue={}, promptVersion={}, policyVersion={}",
                    metricName,
                    tagName,
                    tagValue,
                    promptVersion,
                    policyVersion,
                    ex);
        }
    }

}
