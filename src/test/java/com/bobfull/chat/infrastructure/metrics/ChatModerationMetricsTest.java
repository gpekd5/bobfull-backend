package com.bobfull.chat.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ChatModerationMetricsTest {

    private static final String PROMPT_VERSION = "moderation-prompt-v3-short-fragment-boundary";
    private static final String POLICY_VERSION = "moderation-policy-v2";

    @Test
    void Route_Failure_Final_Status_Counter는_확정된_tag별로_각각_증가한다() {
        // given
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatModerationMetrics metrics = new ChatModerationMetrics(registry);

        // when
        metrics.recordRoute("single_rule", "NO_LLM", POLICY_VERSION);
        metrics.recordRoute("split_rule", "NO_LLM", POLICY_VERSION);
        metrics.recordRoute("llm", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFailure("provider", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFailure("parse", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFailure("validation", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFailure("persistence", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFinalStatus("SAFE", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFinalStatus("FLAGGED", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFinalStatus("ANALYSIS_FAILED", PROMPT_VERSION, POLICY_VERSION);

        // then
        assertThat(count(registry, "bobfull.ai.moderation.route", "route", "single_rule", "NO_LLM"))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.route", "route", "split_rule", "NO_LLM"))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.route", "route", "llm", PROMPT_VERSION))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.failure", "stage", "provider", PROMPT_VERSION))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.failure", "stage", "parse", PROMPT_VERSION))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.failure", "stage", "validation", PROMPT_VERSION))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.failure", "stage", "persistence", PROMPT_VERSION))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.final.status", "status", "SAFE", PROMPT_VERSION))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.final.status", "status", "FLAGGED", PROMPT_VERSION))
                .isEqualTo(1.0);
        assertThat(count(registry, "bobfull.ai.moderation.final.status", "status", "ANALYSIS_FAILED", PROMPT_VERSION))
                .isEqualTo(1.0);
    }

    @Test
    void Kafka_Retry_Counter는_tag없이_호출_횟수만큼_증가한다() {
        // given
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatModerationMetrics metrics = new ChatModerationMetrics(registry);

        // when
        metrics.recordKafkaRetry();
        metrics.recordKafkaRetry();

        // then
        Counter counter = registry.find("bobfull.ai.moderation.kafka.retry").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
        assertThat(counter.getId().getTags()).isEmpty();
    }

    @Test
    void Custom_Metric에는_허용되지_않은_high_cardinality_tag가_없다() {
        // given
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatModerationMetrics metrics = new ChatModerationMetrics(registry);
        metrics.recordRoute("llm", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFailure("provider", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordFinalStatus("SAFE", PROMPT_VERSION, POLICY_VERSION);
        metrics.recordKafkaRetry();

        // when
        Set<String> tagKeys = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("bobfull.ai.moderation"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)
                .collect(Collectors.toSet());

        // then
        assertThat(tagKeys).containsExactlyInAnyOrder(
                "route", "stage", "status", "prompt_version", "policy_version");
        assertThat(tagKeys).doesNotContain(
                "messageId", "eventId", "chatRoomId", "memberId",
                "message_id", "event_id", "chat_room_id", "member_id",
                "content", "raw_content", "exception", "exception_message");
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .allSatisfy(id -> assertThat(id.getTags())
                        .allSatisfy(tag -> assertThat(tag.getValue())
                                .doesNotContain("실제 메시지 원문", "provider secret failure")));
    }

    @Test
    void MeterRegistry_오류가_발생해도_Metric_기록은_예외를_전파하지_않는다() {
        // given
        MeterRegistry registry = mock(MeterRegistry.class);
        given(registry.config()).willThrow(new RuntimeException("registry unavailable"));
        ChatModerationMetrics metrics = new ChatModerationMetrics(registry);

        // when & then
        assertThatCode(() -> metrics.recordRoute("llm", PROMPT_VERSION, POLICY_VERSION)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.recordFailure("provider", PROMPT_VERSION, POLICY_VERSION)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.recordFinalStatus("SAFE", PROMPT_VERSION, POLICY_VERSION)).doesNotThrowAnyException();
        assertThatCode(metrics::recordKafkaRetry).doesNotThrowAnyException();
    }

    private double count(
            SimpleMeterRegistry registry,
            String metricName,
            String dimensionName,
            String dimensionValue,
            String promptVersion) {
        Counter counter = registry.find(metricName)
                .tags(
                        dimensionName, dimensionValue,
                        "prompt_version", promptVersion,
                        "policy_version", POLICY_VERSION)
                .counter();
        assertThat(counter).isNotNull();
        return counter.count();
    }
}
