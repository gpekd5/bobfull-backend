package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.ConfusionAccumulator;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.ConfusionCounts;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.PrecisionRecallF1;
import com.bobfull.chat.infrastructure.ai.ModerationEvaluationMetrics.WilsonInterval;
import org.junit.jupiter.api.Test;

/** OpenAI 호출 없이 순수 계산만 검증한다. API Key 유무와 무관하게 항상 실행된다. */
class ModerationEvaluationMetricsTest {

    @Test
    void TP_FP_FN_TN_누적으로_Precision_Recall_F1을_계산한다() {
        // given: 8 TP, 2 FP, 1 FN, 9 TN
        ConfusionCounts counts = new ConfusionCounts(8, 2, 1, 9);

        // when
        PrecisionRecallF1 result = ModerationEvaluationMetrics.precisionRecallF1(counts);

        // then
        assertThat(result.precision()).isCloseTo(8.0 / 10, within(1e-9));
        assertThat(result.recall()).isCloseTo(8.0 / 9, within(1e-9));
        double expectedF1 = 2 * result.precision() * result.recall() / (result.precision() + result.recall());
        assertThat(result.f1()).isCloseTo(expectedF1, within(1e-9));
    }

    @Test
    void TP와_FP가_모두_0이면_Precision은_0으로_처리한다() {
        ConfusionCounts counts = new ConfusionCounts(0, 0, 5, 10);
        PrecisionRecallF1 result = ModerationEvaluationMetrics.precisionRecallF1(counts);
        assertThat(result.precision()).isZero();
        assertThat(result.recall()).isZero();
        assertThat(result.f1()).isZero();
    }

    @Test
    void 완전히_맞춘_경우_Precision_Recall_F1_모두_1이다() {
        ConfusionCounts counts = new ConfusionCounts(10, 0, 0, 10);
        PrecisionRecallF1 result = ModerationEvaluationMetrics.precisionRecallF1(counts);
        assertThat(result.precision()).isEqualTo(1.0);
        assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.f1()).isEqualTo(1.0);
    }

    @Test
    void Accumulator로_이진_판정을_누적하면_ConfusionCounts와_일치한다() {
        // given: expected=true(4건: 3 hit, 1 miss), expected=false(3건: 1 오탐, 2 정탐)
        ConfusionAccumulator accumulator = new ConfusionAccumulator();
        accumulator.add(true, true);
        accumulator.add(true, true);
        accumulator.add(true, true);
        accumulator.add(true, false);
        accumulator.add(false, true);
        accumulator.add(false, false);
        accumulator.add(false, false);

        // when
        ConfusionCounts counts = accumulator.counts();

        // then
        assertThat(counts.truePositive()).isEqualTo(3);
        assertThat(counts.falseNegative()).isEqualTo(1);
        assertThat(counts.falsePositive()).isEqualTo(1);
        assertThat(counts.trueNegative()).isEqualTo(2);
        assertThat(counts.total()).isEqualTo(7);
    }

    @Test
    void Wilson_구간은_표본이_0이면_0_0을_반환한다() {
        WilsonInterval interval = ModerationEvaluationMetrics.wilson95(0, 0);
        assertThat(interval.lowerBound()).isZero();
        assertThat(interval.upperBound()).isZero();
    }

    @Test
    void Wilson_구간은_항상_0과_1_사이이고_점추정치를_포함한다() {
        int[][] cases = {{40, 40}, {39, 40}, {26, 40}, {0, 40}, {60, 80}, {1, 1}, {0, 1}};
        for (int[] testCase : cases) {
            int successes = testCase[0];
            int total = testCase[1];
            WilsonInterval interval = ModerationEvaluationMetrics.wilson95(successes, total);
            double pointEstimate = (double) successes / total;

            assertThat(interval.lowerBound()).isGreaterThanOrEqualTo(0.0);
            assertThat(interval.upperBound()).isLessThanOrEqualTo(1.0);
            assertThat(interval.lowerBound()).isLessThanOrEqualTo(pointEstimate + 1e-9);
            assertThat(interval.upperBound()).isGreaterThanOrEqualTo(pointEstimate - 1e-9);
        }
    }

    @Test
    void 같은_비율이어도_표본이_커지면_Wilson_구간이_좁아진다() {
        // 지난 리뷰에서 지적된 지점: n=10 카테고리별 표본은 신뢰구간이 넓어 숫자가 쉽게 흔들린다.
        WilsonInterval small = ModerationEvaluationMetrics.wilson95(6, 10);
        WilsonInterval large = ModerationEvaluationMetrics.wilson95(48, 80);

        double smallWidth = small.upperBound() - small.lowerBound();
        double largeWidth = large.upperBound() - large.lowerBound();

        assertThat(largeWidth).isLessThan(smallWidth);
    }
}
