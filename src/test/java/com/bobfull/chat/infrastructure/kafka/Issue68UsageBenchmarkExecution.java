package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.infrastructure.ai.Issue68SdkUsage;
import com.bobfull.chat.infrastructure.ai.Issue68UsageBenchmarkSession;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// 승인된 비운영 사용량 측정기. 품질 평가 실행기의 HOLD를 해제하거나 재사용하지 않는다.
final class Issue68UsageBenchmarkExecution {
    private Issue68UsageBenchmarkExecution() { }

    static Map<String, Object> execute(String bootstrap, String jdbc, Issue68UsageBenchmarkSession session,
            Issue68ExpandedPlan.Unit unit, Issue68HttpBoundary boundary, String endpoint,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> ownerSetup) {
        return execute(bootstrap, jdbc, session, unit, boundary, endpoint, context -> { }, ownerSetup);
    }

    static Map<String, Object> execute(String bootstrap, String jdbc, Issue68UsageBenchmarkSession session,
            Issue68ExpandedPlan.Unit unit, Issue68HttpBoundary boundary, String endpoint,
            java.util.function.Consumer<org.springframework.boot.test.context.assertj.AssertableApplicationContext> setup,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> ownerSetup) {
        var sdk = new Issue68SdkUsage(boundary);
        String started = Instant.now().toString();
        var ownerRef = new java.util.concurrent.atomic.AtomicReference<SizeTimeBatchOwnerLoop>();
        try {
            var result = Issue68OpenAiKafkaHarness.executeExpanded(bootstrap, jdbc, "root", "issue68-fixture-only",
                    unit, boundary, endpoint, context -> {
                        context.getBean(io.micrometer.observation.ObservationRegistry.class).observationConfig().observationHandler(sdk);
                        Issue68A1N1Execution.observeCommits(context, boundary);
                        setup.accept(context);
                    }, owner -> { ownerRef.set(owner); ownerSetup.accept(owner); });
            boolean technical = result.finalRows() == 20 && result.finalLag() == 0 && result.dltRecords() == 0
                    && result.saveFailures() == 0 && result.transactionViolations() == 0 && result.unsafeCommits() == 0
                    && result.commitObservations() > 0 && result.samples().stream().allMatch(s -> s.completed()
                    && s.receivedNs() > 0 && s.committedNs() >= s.receivedNs() && s.returnedNs() >= s.committedNs())
                    && !boundary.calls().isEmpty() && boundary.calls().stream().allMatch(c -> sdk.matches(c.number))
                    && !boundary.attempts().isEmpty() && boundary.attempts().stream()
                    .allMatch(a -> a.status() == 200 && a.settlement().equals("SETTLED"))
                    && result.gateReasons().stream().noneMatch(reason -> reason.equals("USAGE_UNKNOWN")
                    || reason.equals("DB_OFFSET_BOUNDARY") || reason.equals("RESPONSE_MODEL_CHANGED")
                    || reason.equals("TECHNICAL_FAILURE_OBSERVED") || reason.startsWith("LAG_OBSERVATION_FAILED"));
            var report = new LinkedHashMap<String, Object>();
            report.put("unit", unit); report.put("benchmarkStage", session.plan().stage());
            report.put("startedAtUtc", started); report.put("endedAtUtc", Instant.now().toString());
            report.put("result", result); report.put("logical", boundary.calls()); report.put("http", boundary.attempts());
            report.put("sdk", sdk.entries()); report.put("technicalOk", technical);
            report.put("qualityStatus", "RECORDED_NOT_PASS_HISTORICAL_V1_V2_HOLD_PRESERVED");
            report.put("qualityEvaluations", session.plan().quality().stream().filter(row -> row.get("unit").equals(unit)).toList());
            report.put("usage", usage(boundary));
            report.put("batchSizeDistribution", boundary.calls().stream().collect(java.util.stream.Collectors.groupingBy(
                    call -> call.caseIds.size(), java.util.TreeMap::new, java.util.stream.Collectors.counting())));
            report.put("fillRatioAllLogical", boundary.calls().stream().mapToInt(c -> c.caseIds.size()).average().orElse(0) / 10);
            report.put("nativeLogical", boundary.calls().stream().filter(c -> !c.batch).count());
            report.put("batchLogical", boundary.calls().stream().filter(c -> c.batch).count());
            report.put("fillRatioBatchOnly", boundary.calls().stream().filter(c -> c.batch).mapToInt(c -> c.caseIds.size()).average().orElse(0) / 10);
            report.put("providerLatencyMs", latency(boundary.calls().stream().map(c -> (c.ended - c.started) / 1e6).toList()));
            var owner = ownerRef.get();
            report.put("owner", owner == null ? Map.of("bufferWait", "NOT_APPLICABLE") : Map.of(
                    "flushes", java.util.List.copyOf(owner.flushes), "maxBuffered", owner.maxBuffered,
                    "threadViolations", owner.threadViolations, "retryCount", owner.retryCount));
            session.checkpoint("unit-result", report);
            session.plan().completeUnit(technical);
            return report;
        } catch (RuntimeException failure) {
            session.plan().stop(); throw failure;
        } finally {
            session.checkpoint("unit-final", Map.of("unit", unit, "startedAtUtc", started, "endedAtUtc", Instant.now().toString(),
                    "logical", boundary.calls(), "http", boundary.attempts(), "sdk", sdk.entries(),
                    "technicalHolds", session.plan().holds(), "quality", session.plan().quality(), "automaticResume", false));
        }
    }

    private static Map<String, Object> usage(Issue68HttpBoundary boundary) {
        var usages = boundary.attempts().stream().filter(a -> a.usage() != null).map(Issue68HttpBoundary.Attempt::usage).toList();
        long input = usages.stream().mapToLong(u -> u.input()).sum();
        long output = usages.stream().mapToLong(u -> u.output()).sum();
        boolean cacheKnown = usages.stream().allMatch(u -> u.cached() != null);
        long cache = usages.stream().filter(u -> u.cached() != null).mapToLong(u -> u.cached()).sum();
        var result = new LinkedHashMap<String, Object>();
        result.put("input", input); result.put("output", output); result.put("total", usages.stream().mapToLong(u -> u.total()).sum());
        result.put("cached", cacheKnown ? cache : "UNKNOWN_NOT_ZERO");
        result.put("costWithoutCacheNanoUsd", input * 150 + output * 600);
        result.put("costWithCacheNanoUsd", cacheKnown ? input * 150 + output * 600 - cache * 75 : "UNKNOWN_NOT_ESTIMATED");
        result.put("aggregation", "ONE_PER_HTTP_REQUEST_NEVER_COPIED_TO_CASE_ROWS");
        return result;
    }

    private static Map<String, Double> latency(java.util.List<Double> values) {
        return Map.of("avg", values.stream().mapToDouble(v -> v).average().orElse(0),
                "p50", PollBatchMeasurements.percentile(values, .5), "p95", PollBatchMeasurements.percentile(values, .95));
    }
}
