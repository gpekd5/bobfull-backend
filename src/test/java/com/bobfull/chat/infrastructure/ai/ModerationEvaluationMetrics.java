package com.bobfull.chat.infrastructure.ai;

/**
 * Issue #213 Held-out 평가에서 쓰는 순수 계산 유틸이다. OpenAI 호출이나 Spring Context 없이
 * 이미 수집된 expected/actual 쌍으로부터 Precision/Recall/F1과 95% Wilson score interval을 계산한다.
 */
public final class ModerationEvaluationMetrics {

    private ModerationEvaluationMetrics() {
    }

    public record ConfusionCounts(int truePositive, int falsePositive, int falseNegative, int trueNegative) {
        public int total() {
            return truePositive + falsePositive + falseNegative + trueNegative;
        }
    }

    public record PrecisionRecallF1(double precision, double recall, double f1) {
    }

    public record WilsonInterval(double lowerBound, double upperBound) {
    }

    /** expected/actual 이진 판정(예: FLAGGED 여부, 특정 category 포함 여부)을 누적한다. */
    public static final class ConfusionAccumulator {
        private int truePositive;
        private int falsePositive;
        private int falseNegative;
        private int trueNegative;

        public void add(boolean expectedPositive, boolean actualPositive) {
            if (expectedPositive && actualPositive) {
                truePositive++;
            } else if (!expectedPositive && actualPositive) {
                falsePositive++;
            } else if (expectedPositive) {
                falseNegative++;
            } else {
                trueNegative++;
            }
        }

        public ConfusionCounts counts() {
            return new ConfusionCounts(truePositive, falsePositive, falseNegative, trueNegative);
        }
    }

    public static PrecisionRecallF1 precisionRecallF1(ConfusionCounts counts) {
        double precision = denominatorOrZero(counts.truePositive(), counts.truePositive() + counts.falsePositive());
        double recall = denominatorOrZero(counts.truePositive(), counts.truePositive() + counts.falseNegative());
        double f1 = (precision + recall) == 0.0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return new PrecisionRecallF1(precision, recall, f1);
    }

    /**
     * 95% Wilson score interval. 정규근사(Wald)와 달리 표본이 작거나 비율이 0/1에 가까울 때도
     * 신뢰구간이 [0,1] 밖으로 나가지 않는다. Issue #213이 요구하는 "표본이 작을 때 수치가
     * 얼마나 흔들릴 수 있는지"를 보여주는 보조 근거로 쓴다(모집단 정확도 보장이 아님).
     */
    public static WilsonInterval wilson95(int successes, int total) {
        if (total == 0) {
            return new WilsonInterval(0.0, 0.0);
        }
        double z = 1.959963985;
        double p = (double) successes / total;
        double zSquaredOverN = z * z / total;
        double denominator = 1 + zSquaredOverN;
        double center = p + zSquaredOverN / 2;
        double margin = z * Math.sqrt(p * (1 - p) / total + zSquaredOverN / (4.0 * total));
        double lower = (center - margin) / denominator;
        double upper = (center + margin) / denominator;
        return new WilsonInterval(Math.max(0.0, lower), Math.min(1.0, upper));
    }

    private static double denominatorOrZero(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
