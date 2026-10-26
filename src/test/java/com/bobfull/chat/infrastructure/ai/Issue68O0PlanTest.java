package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class Issue68O0PlanTest {
    @Test
    void 양쪽에_같은80건과_Rule1_LLM79를_적용하고_입력계약을_동결한다() throws Exception {
        // given
        var plan = new Issue68O0Plan();
        var cases = Issue68O0Plan.cases();
        var llm = cases.stream().filter(c -> new ModerationRulePolicy().clearFlagged(c.content()).isEmpty()).toList();
        // when
        llm.forEach(c -> plan.begin(List.of(c.id()), false));
        plan.beginPoll(cases.stream().map(c -> Issue68GateV2.evaluate(c, c.expected(), true)).toList(), true);
        for (int i = 0; i < llm.size(); i += 10) {
            var group = llm.subList(i, Math.min(llm.size(), i + 10));
            plan.begin(group.stream().map(Issue68EvaluationContract.Case::id).toList(), group.size() > 1);
        }
        // then
        assertThat(cases).hasSize(80);
        assertThat(llm).hasSize(79);
        assertThat(plan.stopped()).isFalse();
        assertThat(Issue68O0Plan.frozenContract()).containsEntry("nativeLogicalLimit", 79)
                .containsEntry("pollLogicalLimit", 79).containsEntry("totalHttpLimit", 632);
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/o0-contract.json"), new JsonMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Issue68O0Plan.frozenContract()));
    }

    @Test
    void 재전달_중복과_Rule_완료_대상은_새_Provider요청에_포함하지_않는다() {
        // given
        var plan = new Issue68O0Plan();
        plan.begin(List.of("HOLDOUT-SAFE-01"), false);
        // when & then
        assertThatThrownBy(() -> plan.begin(List.of("HOLDOUT-SAFE-01"), false))
                .hasMessage("ISSUE68_O0_LOGICAL_CONTRACT");
        assertThat(plan.stopped()).isTrue();
        assertThatThrownBy(() -> new Issue68O0Plan().begin(List.of("HOLDOUT-PI-01"), false))
                .hasMessage("ISSUE68_O0_LOGICAL_CONTRACT");
    }

    @Test
    void Native_미완료나_품질Gate_미통과시_Poll을_자동실행하지_않는다() {
        // given
        var plan = new Issue68O0Plan();
        var llm = Issue68O0Plan.cases().stream().filter(c -> new ModerationRulePolicy().clearFlagged(c.content()).isEmpty()).toList();
        // when & then
        assertThatThrownBy(() -> plan.beginPoll(List.of(), true)).hasMessage("ISSUE68_O0_NATIVE_GATE_HOLD");
        var another = new Issue68O0Plan();
        llm.forEach(c -> another.begin(List.of(c.id()), false));
        var evaluations = Issue68O0Plan.cases().stream().map(c -> Issue68GateV2.evaluate(c,
                c.id().equals("HOLDOUT-PROFANITY-14")
                        ? new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW) : c.expected(), true)).toList();
        assertThatThrownBy(() -> another.beginPoll(evaluations, true)).hasMessage("ISSUE68_O0_NATIVE_GATE_HOLD");
    }

    @Test
    void Native_공통오류와_Batch_신규회귀를_서로_다르게_기록한다() {
        // given
        var safe = Issue68EvaluationContract.heldout().get(0);
        var fp = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        // when
        var common = Issue68GateV2.paired(safe, fp, fp);
        var regression = Issue68GateV2.paired(safe, safe.expected(), fp);
        // then
        assertThat(common.commonErrors()).contains("COMMON_FP");
        assertThat(common.regressions()).doesNotContain("NEW_FP");
        assertThat(regression.regressions()).contains("NEW_FP", "CATEGORY_REGRESSION");
        assertThat(Issue68GateV2.evaluate(safe, fp, true).v2Blockers()).contains("FP_HUMAN_REVIEW");
    }
}
