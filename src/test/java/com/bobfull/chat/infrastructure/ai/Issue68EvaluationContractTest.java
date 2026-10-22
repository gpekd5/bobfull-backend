package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Issue68EvaluationContractTest {
    @Test
    void 과거_v2_80건과_진단20을_재사용하고_새40은_포함하지_않는다() {
        // given
        var all = Issue68EvaluationContract.heldout();
        var diagnostic = Issue68EvaluationContract.diagnostic();
        // when & then
        assertThat(all).hasSize(80);
        assertThat(diagnostic).hasSize(20);
        assertThat(all).containsAll(diagnostic);
        assertThat(all).allMatch(c -> c.id().startsWith("HOLDOUT-"));
        assertThat(all.stream().filter(c -> c.expected().riskLevel() == RiskLevel.HIGH)).hasSize(10);
        assertThat(all.stream().filter(c -> new ModerationRulePolicy().clearFlagged(c.content()).isPresent())).hasSize(1);
        assertThat(diagnostic).allMatch(c -> new ModerationRulePolicy().clearFlagged(c.content()).isEmpty());
        assertThat(Issue68EvaluationContract.groundTruthSha256()).isEqualTo(Issue68EvaluationContract.FROZEN_GT_SHA256);
        System.out.println("ISSUE68_GT_SHA256=" + Issue68EvaluationContract.groundTruthSha256());
    }

    @Test
    void Pilot을_포함한_880건_Matrix와_고정_순서를_유지한다() {
        // given
        var stages = Issue68EvaluationContract.matrix();
        // when & then
        assertThat(stages.stream().mapToInt(s -> 2 * s.repeats() * (s.input().equals("diagnostic20") ? 20 : 80)).sum())
                .isEqualTo(880).isLessThanOrEqualTo(1000);
        assertThat(Issue68EvaluationContract.ordered(55)).isEqualTo(Issue68EvaluationContract.ordered(55));
        assertThat(Issue68EvaluationContract.ordered(55)).isNotEqualTo(Issue68EvaluationContract.ordered(68));
        var forecasts = Issue68EvaluationContract.forecast();
        assertThat(forecasts.stream().mapToInt(Issue68EvaluationContract.Forecast::nativeCalls).sum()).isEqualTo(435);
        assertThat(forecasts.stream().mapToInt(Issue68EvaluationContract.Forecast::onMinCalls).sum()).isEqualTo(62);
        System.out.println("ISSUE68_FORECAST=" + Issue55MicroBatchEvaluation.JSON.writeValueAsString(forecasts));
    }

    @Test
    void Native_공통_오류라도_FN과_HIGH_미달은_진행_Gate를_막는다() {
        // given
        var c = Issue68EvaluationContract.heldout().stream().filter(x -> x.id().equals("HOLDOUT-PROFANITY-16")).findFirst().orElseThrow();
        var safe = new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
        var medium = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        // when & then
        assertThat(Issue68EvaluationContract.gate(c, safe, true)).containsExactly("FN", "GT_HIGH_DOWNGRADE");
        assertThat(Issue68EvaluationContract.gate(c, medium, true)).containsExactly("GT_HIGH_DOWNGRADE");
        assertThat(Issue68EvaluationContract.gate(c, null, false)).containsExactly("UNRESOLVED");
    }

    @Test
    void 새_FP와_정책_금지_조합은_Validation_성공과_별개로_차단한다() {
        // given
        var c = Issue68EvaluationContract.diagnostic().get(0);
        var flagged = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.LOW);
        // when & then
        assertThat(Issue68EvaluationContract.pairedGate(c, c.expected(), flagged)).contains("NEW_FP", "RESULT_REGRESSION");
        assertThat(Issue68EvaluationContract.gate(c, flagged, true)).contains("POLICY_COMBINATION");
    }

    @Test
    void UNRESOLVED를_TN이나_정답으로_세지_않는다() {
        // given
        var cases = Issue68EvaluationContract.diagnostic().subList(0, 2);
        // when
        var quality = Issue68EvaluationContract.quality(cases, java.util.Map.of(cases.get(0).id(), cases.get(0).expected()));
        // then
        assertThat(quality.cases()).isEqualTo(2);
        assertThat(quality.unresolved()).isEqualTo(1);
        assertThat(quality.resultExact()).isEqualTo(1);
        assertThat(quality.tp() + quality.fp() + quality.fn() + quality.tn()).isEqualTo(1);
    }
}
