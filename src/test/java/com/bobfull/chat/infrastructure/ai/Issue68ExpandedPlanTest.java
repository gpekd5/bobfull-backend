package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Issue68ExpandedPlanTest {
    @Test
    void 승인한_42개_실행단위와_3000건을_여섯조건에_동일하게_배정한다() {
        // given & when
        var units = Issue68ExpandedPlan.matrix();
        // then
        assertThat(units).hasSize(42).doesNotHaveDuplicates();
        assertThat(units.stream().mapToInt(u -> u.stage().count).sum()).isEqualTo(3000);
        for (var stage : Issue68ExpandedPlan.Stage.values()) {
            var selected = units.stream().filter(u -> u.stage() == stage).toList();
            assertThat(selected).hasSize(stage == Issue68ExpandedPlan.Stage.PILOT ? 6
                    : stage == Issue68ExpandedPlan.Stage.MAIN ? 24 : 12);
            assertThat(Issue68ExpandedPlan.cases(stage)).hasSize(stage.count);
        }
        assertThat(Issue68EvaluationContract.groundTruthSha256()).isEqualTo(Issue68EvaluationContract.FROZEN_GT_SHA256);
        assertThat(Issue68GateV2.metadataSha256()).isEqualTo(Issue68GateV2.FROZEN_METADATA_SHA256);
    }

    @Test
    void 단계별_Token_추정과_SDK상한은_실제Usage가_아닌_계획치로_분리한다() {
        // given & when
        var values = Issue68ExpandedPlan.forecast();
        // then
        assertThat(values.stream().mapToInt(Issue68ExpandedPlan.Forecast::messages).sum()).isEqualTo(3000);
        assertThat(values.stream().mapToInt(Issue68ExpandedPlan.Forecast::logicalLowShape).sum()).isEqualTo(744);
        assertThat(values.stream().mapToInt(Issue68ExpandedPlan.Forecast::logicalUnderfill).sum()).isEqualTo(2964);
        assertThat(values.stream().mapToLong(Issue68ExpandedPlan.Forecast::costLowShapeNanoUsd).sum()).isEqualTo(359_547_300L);
        assertThat(values.stream().mapToLong(Issue68ExpandedPlan.Forecast::costUnderfillNanoUsd).sum()).isEqualTo(690_765_300L);
        for (var value : values) assertThat(value.logicalUnderfill() * 4).isEqualTo(value.stage().httpLimit);
    }

    @Test
    void Native_FP와_공통FP는_여섯조건에서_원래_GT오류로_보존한다() {
        // given
        var plan = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT);
        var fp = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.SPAM), RiskLevel.MEDIUM);
        // when
        for (var unit : Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == plan.stage()).toList()) {
            plan.start(unit);
            var cases = Issue68ExpandedPlan.cases(plan.stage());
            int size = unit.mode() == Issue68ExpandedPlan.Mode.NATIVE ? 1 : 10;
            for (int i = 0; i < cases.size(); i += size) {
                var ids = cases.subList(i, Math.min(i + size, cases.size())).stream().map(Issue68EvaluationContract.Case::id).toList();
                plan.begin(ids, ids.size() > 1);
            }
            cases.forEach(c -> plan.committed(c, c.id().equals("HOLDOUT-SAFE-17") ? fp : c.expected(), true));
            plan.completeUnit(true);
        }
        // then
        assertThat(plan.complete()).isTrue();
        assertThat(plan.evaluations().stream().filter(e -> e.id().equals("HOLDOUT-SAFE-17")))
                .allSatisfy(e -> assertThat(e.exactAndReview().resultExact()).isFalse());
        assertThat(plan.holds()).isEmpty();
    }

    @Test
    void Native_기준선과_이전조건_완료_없이_다음조건을_시작하지_않는다() {
        // given
        var plan = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT);
        // when & then
        assertThatThrownBy(() -> plan.start(Issue68ExpandedPlan.matrix().get(1)))
                .hasMessage("ISSUE68_EXPANDED_UNIT_ORDER");
        assertThat(plan.stopped()).isTrue();
        assertThat(new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT).complete()).isFalse();
    }

    @Test
    void GT정답_실행도_단계별_명시적_완료만_허용하고_Rule은_Provider에서_제외한다() {
        // given
        var plan = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.MAIN);
        // when
        for (var unit : Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == plan.stage()).toList()) {
            completeUnit(plan, unit);
        }
        // then
        assertThat(plan.complete()).isTrue();
        assertThat(plan.evaluations()).hasSize(1920);
        assertThat(Issue68ExpandedPlan.cases(plan.stage()).stream()
                .filter(c -> new ModerationRulePolicy().clearFlagged(c.content()).isPresent())).hasSize(1);
    }

    @Test
    void 같은단위의_중복_입력이나_다른_Dataset_ID는_차단한다() {
        // given
        var plan = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT);
        plan.start(Issue68ExpandedPlan.matrix().get(0));
        var id = Issue68EvaluationContract.diagnostic().get(0).id();
        plan.begin(List.of(id), false);
        // when & then
        assertThatThrownBy(() -> plan.begin(List.of(id), false)).hasMessage("ISSUE68_EXPANDED_LOGICAL_CONTRACT");
        var other = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT);
        other.start(Issue68ExpandedPlan.matrix().get(0));
        assertThatThrownBy(() -> other.begin(List.of("HOLDOUT-PI-01"), false))
                .hasMessage("ISSUE68_EXPANDED_LOGICAL_CONTRACT");
    }

    @Test
    void Native_일반FP는_남기고_Batch_새FP나_Critical하향은_전체를_HOLD한다() {
        // given
        var plan = new Issue68ExpandedPlan(Issue68ExpandedPlan.Stage.PILOT);
        completeUnit(plan, Issue68ExpandedPlan.matrix().get(0));
        plan.start(Issue68ExpandedPlan.matrix().get(1));
        var safe = Issue68EvaluationContract.diagnostic().get(0);
        plan.begin(List.of(safe.id()), false);
        var fp = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.SPAM), RiskLevel.MEDIUM);
        // when
        plan.committed(safe, fp, true);
        // then
        assertThat(plan.holds().values().stream().flatMap(List::stream)).contains("NEW_FP");
        assertThatThrownBy(plan::requireActive).hasMessage("ISSUE68_EXPANDED_HOLD");
        var critical = Issue68EvaluationContract.heldout().stream().filter(c -> c.id().endsWith("PROFANITY-15")).findFirst().orElseThrow();
        assertThat(Issue68GateV2.executionBlockers(critical,
                new ModerationResult(ModerationResultType.FLAGGED, critical.expected().categories(), RiskLevel.MEDIUM),
                true, true, null)).contains("CRITICAL_RISK_DOWNGRADE");
    }

    @Test
    void 문맥의존_HIGH_MEDIUM은_V1오류를_보존하고_V2_검토신호와_구분한다() {
        // given
        var c = Issue68EvaluationContract.diagnostic().stream().filter(x -> x.id().endsWith("PROFANITY-16")).findFirst().orElseThrow();
        var result = new ModerationResult(ModerationResultType.FLAGGED, c.expected().categories(), RiskLevel.MEDIUM);
        // when
        var detail = Issue68GateV2.evaluate(c, result, true);
        // then
        assertThat(detail.v1Blockers()).contains("GT_HIGH_DOWNGRADE");
        assertThat(detail.v2Blockers()).isEmpty();
        assertThat(detail.v2Notes()).contains("NONCRITICAL_HIGH_MEDIUM_REVIEW_PRESERVED");
        assertThat(Issue68GateV2.executionBlockers(c,
                new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW), true, true, null))
                .contains("FN", "REVIEW_TARGET_MISS");
    }

    public static void completeUnit(Issue68ExpandedPlan plan, Issue68ExpandedPlan.Unit unit) {
        plan.start(unit);
        var cases = Issue68ExpandedPlan.cases(unit.stage());
        var targets = cases.stream().filter(c -> new ModerationRulePolicy().clearFlagged(c.content()).isEmpty()).toList();
        int size = unit.mode() == Issue68ExpandedPlan.Mode.NATIVE ? 1 : 10;
        for (int i = 0; i < targets.size(); i += size) {
            var ids = targets.subList(i, Math.min(i + size, targets.size())).stream().map(Issue68EvaluationContract.Case::id).toList();
            plan.begin(ids, ids.size() > 1);
        }
        cases.forEach(c -> plan.committed(c, c.expected(), true));
        plan.completeUnit(true);
    }
}
