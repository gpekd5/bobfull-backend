package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchUsage;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.CaseObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Issue55MicroBatchF0Test {
    @Test
    void 고정20건은_기존_v2_GT를_재사용하며_모두_Rule_miss다() {
        // given / when
        var targets = Issue55MicroBatchF0.targets();
        // then: targets() 자체가 누락 ID/Rule hit를 거부한다.
        assertThat(targets).hasSize(20);
        assertThat(targets.stream().map(t -> t.testCase().id()).toList()).isEqualTo(Issue55MicroBatchF0.IDS);
        assertThat(targets.stream().map(t -> t.neutralId()).toList()).doesNotHaveDuplicates()
                .startsWith("m001").endsWith("m020");
        assertThat(targets.stream().filter(t -> t.testCase().expectedResult() == ModerationResultType.FLAGGED).count()).isEqualTo(9);
        assertThat(targets.stream().filter(t -> t.testCase().expectedRiskLevel() == RiskLevel.HIGH).count()).isEqualTo(7);
    }

    @Test
    void 교차순서는_각조건3회_78호출이고_Batch구성과_ID가_반복간_같다() {
        // given / when
        var schedule = Issue55MicroBatchF0.schedule();
        // then
        assertThat(schedule.stream().mapToInt(t -> Issue55MicroBatchF0.batches(t.batchSize()).size()).sum()).isEqualTo(78);
        for (int repeat = 1; repeat <= 3; repeat++) {
            int r = repeat;
            assertThat(schedule.stream().filter(t -> t.repeat() == r).map(t -> t.batchSize()).toList())
                    .containsExactlyInAnyOrder(1, 5, 10);
        }
        for (int size : List.of(1, 5, 10)) {
            var batches = Issue55MicroBatchF0.batches(size);
            assertThat(batches.stream().flatMap(b -> b.targets().stream()).toList()).isEqualTo(Issue55MicroBatchF0.targets());
            var inputs = batches.stream().map(b -> Issue55MicroBatchEvaluation.JSON.writeValueAsString(b.input())).toList();
            assertThat(inputs).isEqualTo(Issue55MicroBatchF0.batches(size).stream()
                    .map(b -> Issue55MicroBatchEvaluation.JSON.writeValueAsString(b.input())).toList());
            assertThat(inputs).allSatisfy(json -> assertThat(json).doesNotContain("HOLDOUT-", "expected", "caseType"));
        }
    }

    @Test
    void 사전추정은_78회와_누적예산을_확인하며_실제Usage라고_하지_않는다() {
        // given / when
        var forecast = Issue55MicroBatchF0.forecast();
        // then
        assertThat(forecast.stream().mapToInt(f -> f.logicalCalls()).sum()).isEqualTo(78);
        assertThat(forecast.stream().mapToLong(f -> f.outputCapSum()).sum()).isEqualTo(23_040);
        assertThat(Issue55MicroBatchF0.PRIOR_ESTIMATED_USD
                + forecast.stream().mapToDouble(f -> f.allFourAttemptsEstimateUsd()).sum()).isLessThan(.50);
        forecast.forEach(f -> System.out.println("F0_LOCAL_TOKENIZER_FORECAST " + f));
    }

    @Test
    void 단건변동과_Batch변동은_분리하며_안정된_오답도_정답으로_간주하지_않는다() {
        // given
        var runs = new ArrayList<Issue55MicroBatchF0.Run>();
        for (var t : Issue55MicroBatchF0.schedule()) {
            var rows = Issue55MicroBatchF0.targets().stream().map(target -> {
                var expected = Issue55MicroBatchEvaluation.expected(target.testCase());
                var actual = expected;
                if (target.testCase().id().equals("HOLDOUT-SAFE-17") && (t.batchSize() != 1 || t.repeat() == 2)) {
                    actual = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
                }
                return new CaseObservation(target.testCase().id(), "fake", expected, actual, null);
            }).toList();
            runs.add(new Issue55MicroBatchF0.Run(t, true, List.of(call(rows)), null));
        }
        // when
        var values = Issue55MicroBatchF0.variability(runs).stream().filter(v -> v.caseId().equals("HOLDOUT-SAFE-17")).toList();
        // then
        assertThat(values.get(0).distinctTuples()).isEqualTo(2);
        assertThat(values.get(0).exactMatches()).isEqualTo(2);
        assertThat(values.get(1).distinctTuples()).isEqualTo(1);
        assertThat(values.get(1).exactMatches()).isZero();
        assertThat(Issue55MicroBatchF0.paired(runs).stream().flatMap(p -> p.changes().stream()).filter(c -> c.newFp()).count()).isEqualTo(4);
    }

    @Test
    void 부분집합품질은20건이며_Usage는Batch당_한번만_합산한다() {
        // given
        var calls = Issue55MicroBatchF0.batches(5).stream().map(b -> call(b.targets().stream().map(t -> {
            var expected = Issue55MicroBatchEvaluation.expected(t.testCase());
            return new CaseObservation(t.testCase().id(), "fake", expected, expected, null);
        }).toList())).toList();
        // when
        var stats = Issue55MicroBatchF0.stats(calls, 4, 8_000_000);
        // then
        assertThat(stats.quality().count()).isEqualTo(20);
        assertThat(stats.quality().resultMatches()).isEqualTo(20);
        assertThat(stats.inputTokens()).isEqualTo(400);
        assertThat(stats.outputTokens()).isEqualTo(80);
        assertThat(stats.cachedInputTokens()).isEqualTo(320);
        assertThat(stats.logicalCalls()).isEqualTo(4);
        assertThat(stats.cacheAwareEstimatedUsd()).isLessThan(stats.noCacheEstimatedUsd());
    }

    @Test
    void Validator통과와_별개로_PROFANITY_LOW_정책조합을_진단한다() {
        // given
        var low = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.LOW);
        var medium = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        // when / then
        assertThat(Issue55MicroBatchF0.policyViolation(low)).isTrue();
        assertThat(Issue55MicroBatchF0.policyViolation(medium)).isFalse();
        assertThat(Issue55MicroBatchF0.policyViolation(null)).isFalse();
    }

    private static BatchObservation call(List<CaseObservation> rows) {
        return new BatchObservation("fake", rows.size(), 128 * rows.size(), new BatchUsage(100, 20, 120, 80L),
                "fake", 1_000_000, 1_000_000, null, null, rows);
    }
}
