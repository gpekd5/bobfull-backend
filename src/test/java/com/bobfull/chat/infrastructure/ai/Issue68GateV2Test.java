package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class Issue68GateV2Test {
    @Test
    void 전체80의_별도_metadata는_GT를_변경하지_않고_명시성_기준으로_분류한다() throws Exception {
        // given
        var rows = Issue68GateV2.metadata();
        // when
        var groups = rows.stream().collect(Collectors.groupingBy(Issue68GateV2.Metadata::profile, Collectors.counting()));
        // then
        assertThat(rows).hasSize(80);
        assertThat(rows.stream().map(Issue68GateV2.Metadata::id).distinct().count()).isEqualTo(80);
        assertThat(groups).containsEntry(Issue68GateV2.Profile.CRITICAL, 25L)
                .containsEntry(Issue68GateV2.Profile.CONTEXT_DEPENDENT, 14L)
                .containsEntry(Issue68GateV2.Profile.RISK_BOUNDARY, 2L)
                .containsEntry(Issue68GateV2.Profile.STANDARD, 39L);
        assertThat(Issue68EvaluationContract.groundTruthSha256()).isEqualTo(Issue68EvaluationContract.FROZEN_GT_SHA256);
        assertThat(rows).allMatch(r -> !r.reason().isBlank() && r.contentSha256().matches("[a-f0-9]{64}"));
        assertThat(Issue68GateV2.metadataSha256()).isEqualTo(Issue68GateV2.FROZEN_METADATA_SHA256);
        Issue68GateV2.requireFrozen();
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/gate-v2-metadata.json"), new JsonMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Issue68GateV2.manifest()));
    }

    @Test
    void HIGH_MEDIUM은_V1_HOLD를_유지하고_비Critical의_검토보존만_별도_기록한다() {
        // given
        for (String id : List.of("HOLDOUT-PROFANITY-16", "HOLDOUT-SPAM-11", "HOLDOUT-SPAM-16")) {
            var c = find(id);
            var actual = new ModerationResult(c.expected().result(), c.expected().categories(), RiskLevel.MEDIUM);
            // when
            var result = Issue68GateV2.evaluate(c, actual, true);
            // then
            assertThat(result.v1Blockers()).contains("GT_HIGH_DOWNGRADE");
            assertThat(result.v2Blockers()).isEmpty();
            assertThat(result.v2Notes()).contains("NONCRITICAL_HIGH_MEDIUM_REVIEW_PRESERVED");
            assertThat(result.exactAndReview().riskExact()).isFalse();
        }
    }

    @Test
    void 엄격보호_HIGH는_MEDIUM에서도_중단하고_개인정보_Category_손실도_중단한다() {
        // given
        for (String id : List.of("HOLDOUT-PROFANITY-14", "HOLDOUT-PROFANITY-15", "HOLDOUT-SPAM-08")) {
            var c = find(id);
            // when & then
            assertThat(Issue68GateV2.evaluate(c,
                    new ModerationResult(c.expected().result(), c.expected().categories(), RiskLevel.MEDIUM), true)
                    .v2Blockers()).contains("CRITICAL_RISK_DOWNGRADE");
        }
        var pii = find("HOLDOUT-PI-01");
        assertThat(Issue68GateV2.evaluate(pii,
                new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM), true)
                .v2Blockers()).contains("CRITICAL_CATEGORY_MISS");
    }

    @Test
    void 문맥의존도_SAFE_LOW로_검토신호를_잃으면_FN과_누락을_지우지_않는다() {
        // given
        var c = find("HOLDOUT-PROFANITY-16");
        // when
        var result = Issue68GateV2.evaluate(c, new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW), true);
        // then
        assertThat(result.v2Blockers()).contains("FN", "REVIEW_TARGET_MISS");
        assertThat(result.v2Notes()).contains("CONTEXT_DEPENDENT_REPORT_WITHOUT_EXCLUSION");
        assertThat(result.exactAndReview().resultExact()).isFalse();
        assertThat(result.v1Blockers()).contains("FN", "GT_HIGH_DOWNGRADE");
    }

    @Test
    void 모든_GT_검토대상의_SAFE_LOW_누락과_모든_SAFE의_FP는_차단한다() {
        // given
        for (var c : Issue68EvaluationContract.heldout()) {
            // when & then
            if (Issue68ReviewGateDraft.reviewTarget(c.expected())) {
                assertThat(Issue68GateV2.evaluate(c,
                        new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW), true).v2Blockers())
                        .as(c.id()).contains("REVIEW_TARGET_MISS");
                assertThat(Issue68GateV2.evaluate(c,
                        new ModerationResult(ModerationResultType.FLAGGED, c.expected().categories(), RiskLevel.LOW), true).v2Blockers())
                        .as(c.id()).contains("REVIEW_TARGET_MISS");
            } else {
                assertThat(Issue68GateV2.evaluate(c, new ModerationResult(ModerationResultType.FLAGGED,
                        Set.of(ModerationCategory.SPAM), RiskLevel.MEDIUM), true).v2Blockers())
                        .as(c.id()).contains("FP_HUMAN_REVIEW");
            }
        }
    }

    @Test
    void 미완료와_입력변조는_완전일치나_정상_Metadata로_처리하지_않는다() {
        // given
        var c = find("HOLDOUT-SAFE-01");
        // when & then
        assertThat(Issue68GateV2.evaluate(c, c.expected(), false).v2Blockers()).containsExactly("UNRESOLVED");
        assertThatThrownBy(() -> Issue68GateV2.evaluate(
                new Issue68EvaluationContract.Case(c.id(), "변조 fixture", c.expected()), c.expected(), true))
                .hasMessage("ISSUE68_METADATA_CONTENT_CHANGED");
    }

    private static Issue68EvaluationContract.Case find(String id) {
        return Issue68EvaluationContract.heldout().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
