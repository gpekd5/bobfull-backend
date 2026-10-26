package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;

class Issue68ReviewGateDraftTest {

    // 합성 enum fixture다. PROFANITY-16 등의 의미/GT를 결정하는 테스트가 아니다.
    private static final Issue68EvaluationContract.Case HIGH = new Issue68EvaluationContract.Case(
            "SYNTHETIC-HIGH", "기술 계약 fixture", flagged(RiskLevel.HIGH));

    @Test
    void HIGH에서_MEDIUM은_V1_하향을_유지하며_검토_신호만_별도로_기록한다() {
        var result = Issue68ReviewGateDraft.compare(HIGH, flagged(RiskLevel.MEDIUM), true);

        assertThat(result.v1Reasons()).containsExactly("GT_HIGH_DOWNGRADE");
        assertThat(result.resultExact()).isTrue();
        assertThat(result.categoryExact()).isTrue();
        assertThat(result.riskExact()).isFalse();
        assertThat(result.expectedReviewTarget()).isTrue();
        assertThat(result.actualReviewTarget()).isTrue();
        assertThat(result.highToMediumReviewPreserved()).isTrue();
        assertThat(result.decision()).isEqualTo("HUMAN_APPROVAL_REQUIRED");
    }

    @Test
    void SAFE와_FLAGGED_LOW는_모두_검토_신호_누락이며_FN은_별도다() {
        var safe = Issue68ReviewGateDraft.compare(HIGH,
                new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW), true);
        var low = Issue68ReviewGateDraft.compare(HIGH, flagged(RiskLevel.LOW), true);

        assertThat(safe.draftHoldReasons()).contains("FN", "REVIEW_TARGET_MISS");
        assertThat(low.draftHoldReasons()).contains("REVIEW_TARGET_MISS", "POLICY_COMBINATION").doesNotContain("FN");
        assertThat(safe.actualReviewTarget()).isFalse();
        assertThat(low.actualReviewTarget()).isFalse();
    }

    @Test
    void 결과가_있어도_DB_미완료면_UNRESOLVED이고_검토_성공이_아니다() {
        var result = Issue68ReviewGateDraft.compare(HIGH, HIGH.expected(), false);

        assertThat(result.v1Reasons()).containsExactly("UNRESOLVED");
        assertThat(result.draftHoldReasons()).containsExactly("UNRESOLVED");
        assertThat(result.actualReviewTarget()).isFalse();
        assertThat(result.resultExact()).isFalse();
        assertThat(result.categoryExact()).isFalse();
        assertThat(result.riskExact()).isFalse();
    }

    @Test
    void 응답_누락은_SAFE나_TN으로_대체하지_않는다() {
        var result = Issue68ReviewGateDraft.compare(HIGH, null, false);

        assertThat(result.resultExact()).isFalse();
        assertThat(result.riskExact()).isFalse();
        assertThat(result.draftHoldReasons()).containsExactly("UNRESOLVED");
    }

    @Test
    void FP와_Category_불일치를_검토신호_유지만으로_허용하지_않는다() {
        var safe = new Issue68EvaluationContract.Case("SYNTHETIC-SAFE", "기술 계약 fixture",
                new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW));
        var fp = Issue68ReviewGateDraft.compare(safe, flagged(RiskLevel.MEDIUM), true);
        var category = Issue68ReviewGateDraft.compare(HIGH,
                new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.SPAM), RiskLevel.MEDIUM), true);

        assertThat(fp.draftHoldReasons()).contains("FP_REQUIRES_HUMAN", "CATEGORY_MISMATCH_REQUIRES_HUMAN");
        assertThat(category.actualReviewTarget()).isTrue();
        assertThat(category.highToMediumReviewPreserved()).isFalse();
        assertThat(category.draftHoldReasons()).contains("CATEGORY_MISMATCH_REQUIRES_HUMAN");
    }

    @Test
    void 완전일치해도_초안은_PASS를_선언하지_않고_GT_hash를_유지한다() {
        var result = Issue68ReviewGateDraft.compare(HIGH, HIGH.expected(), true);

        assertThat(result.v1Reasons()).isEmpty();
        assertThat(result.draftHoldReasons()).isEmpty();
        assertThat(result.decision()).isEqualTo("HUMAN_APPROVAL_REQUIRED");
        assertThat(Issue68EvaluationContract.heldout()).hasSize(80);
        assertThat(Issue68EvaluationContract.groundTruthSha256())
                .isEqualTo("2892ac234eea3ae79da9a1d13fd10237393393fddb87cb2b24180817a9a7823b");
    }

    @Test
    void Review_Target_Recall의_분모에서_미완료_검토대상을_제외하지_않는다() {
        var observations = List.of(
                Issue68ReviewGateDraft.compare(HIGH, HIGH.expected(), true),
                Issue68ReviewGateDraft.compare(HIGH, flagged(RiskLevel.MEDIUM), true),
                Issue68ReviewGateDraft.compare(HIGH, null, false));

        long expectedTargets = observations.stream().filter(Issue68ReviewGateDraft.Comparison::expectedReviewTarget).count();
        long retainedTargets = observations.stream().filter(o -> o.expectedReviewTarget() && o.actualReviewTarget()).count();
        assertThat(expectedTargets).isEqualTo(3);
        assertThat(retainedTargets).isEqualTo(2);
        assertThat((double) retainedTargets / expectedTargets).isEqualTo(2.0 / 3);
        assertThat(observations.stream().filter(o -> o.draftHoldReasons().contains("UNRESOLVED")).count()).isEqualTo(1);
    }

    private static ModerationResult flagged(RiskLevel risk) {
        return new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), risk);
    }
}
