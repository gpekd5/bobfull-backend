package com.bobfull.chat.infrastructure.kafka;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// #62 테스트 전용 집계. 원본 표본을 보존하고 percentile 평균을 전체 percentile로 사용하지 않는다.
final class PollBatchMeasurements {
    private PollBatchMeasurements() {
    }

    static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty() || quantile <= 0 || quantile > 1) {
            throw new IllegalArgumentException("표본과 0 < quantile <= 1이 필요합니다.");
        }
        var sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(quantile * sorted.size()) - 1);
    }

    static Map<Integer, Long> distribution(List<Integer> sizes) {
        Map<Integer, Long> result = new TreeMap<>();
        sizes.forEach(size -> result.merge(size, 1L, Long::sum));
        return result;
    }

    static double mean(List<Integer> sizes) {
        return sizes.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    record Sample(int ordinal, boolean rule, long receivedNs, long flushReturnedNs, long committedNs,
                  long repositoryReturnedNs, long publishAckNs, double createdToCommitMs) {
        double receivedToCommitMs() {
            return millis(committedNs - receivedNs);
        }
    }

    record Lag(long elapsedNs, long endOffset, long committedOffset) {
        long value() {
            return endOffset - committedOffset;
        }
    }

    record Request(String path, int size, long startedNs, long endedNs, boolean failed) {
    }

    record Run(String scenario, String mode, int repeat, int messages, int intervalMs, List<Integer> polls,
               int emptyPolls, List<Integer> batches, List<Request> requests, List<Sample> samples,
               List<Lag> lag, int listenerFailures, int saveFailures, int incomplete, int finalRows,
               int transactionViolations, double drainAfterLastPublishAckMs, Map<String, Object> metrics,
               Map<String, Object> consumerConfig) {
        Map<String, Object> summary() {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("scenario", scenario);
            result.put("mode", mode);
            result.put("repeat", repeat);
            result.put("messages", messages);
            result.put("ruleMessages", samples.stream().filter(Sample::rule).count());
            result.put("pollDistribution", distribution(polls));
            result.put("meanPollSize", mean(polls));
            result.put("batchDistribution", distribution(batches));
            result.put("meanBatchSize", batches.isEmpty() ? null : mean(batches));
            result.put("meanBatchFill", batches.isEmpty() ? null : mean(batches) / 10.0);
            result.put("nativeCalls", requests.stream().filter(r -> r.path().equals("native")).count());
            result.put("batchCalls", requests.stream().filter(r -> r.path().equals("batch")).count());
            result.put("nativeMessages", requests.stream().filter(r -> r.path().equals("native")).mapToInt(Request::size).sum());
            result.put("batchMessages", batches.stream().mapToInt(Integer::intValue).sum());
            result.put("receivedToCommitP50Ms", percentile(samples.stream().map(Sample::receivedToCommitMs).toList(), .5));
            result.put("receivedToCommitP95Ms", percentile(samples.stream().map(Sample::receivedToCommitMs).toList(), .95));
            result.put("publishAckToCommitP95Ms", percentile(samples.stream()
                    .map(s -> millis(s.committedNs() - s.publishAckNs())).toList(), .95));
            result.put("observedPeakLag", lag.stream().mapToLong(Lag::value).max().orElseThrow());
            result.put("finalLag", lag.get(lag.size() - 1).value());
            result.put("drainAfterLastPublishAckMs", drainAfterLastPublishAckMs);
            result.put("listenerFailures", listenerFailures);
            result.put("saveFailures", saveFailures);
            result.put("incomplete", incomplete);
            result.put("finalRows", finalRows);
            result.put("transactionViolations", transactionViolations);
            return result;
        }
    }
}
