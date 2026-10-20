package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationResultValidationSupport;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

// 초안의 기계적 계약만 검증한다. Human 라벨 확정이나 의미적 신규성을 증명하지 않는다.
class Issue55MicroBatchNew40DraftTest {
    private static final Path DRAFT = Path.of("docs/110-records/evidence/v3/55-ai-moderation-efficiency/microbatch-new40-human-review-draft.json");

    @Test
    void 신규40건은_Human미확정_초안이며_Provider평가가_허용되지_않는다() throws IOException {
        // given / when
        var draft = read();
        // then
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT_NOT_HUMAN_REVIEWED");
        assertThat(draft.path("humanConfirmed").asBoolean()).isFalse();
        assertThat(draft.path("providerEvaluationAllowed").asBoolean()).isFalse();
        assertThat(draft.path("cases").size()).isEqualTo(40);
        assertThat(draft.path("candidateFrozenSourceSha256").asText()).isEqualTo(Issue55MicroBatchF0.sha256(
                Files.readString(Path.of("src/test/java/com/bobfull/chat/infrastructure/ai/Issue55MicroBatchChecklist.java"))));
    }

    @Test
    void 기존Heldout와_Challenge의_정규화원문과_겹치지_않고_Rule미적중40건이다() throws IOException {
        // given
        var old = SpringAiModerationHeldoutEvaluationTest.heldoutCasesPolicyV2().stream()
                .map(c -> normalized(c.message())).collect(Collectors.toSet());
        SpringAiModerationHeldoutEvaluationTest.challengeCases().forEach(c -> old.add(normalized(c.message())));
        var ids = new HashSet<String>();
        var messages = new HashSet<String>();
        var rule = new ModerationRulePolicy();
        // when / then: 동일 문장 불포함만 확인하며 패러프레이즈는 Human 검토 사항이다.
        for (var row : read().path("cases")) {
            String message = row.path("message").asText();
            assertThat(ids.add(row.path("id").asText())).isTrue();
            assertThat(messages.add(normalized(message))).isTrue();
            assertThat(old).doesNotContain(normalized(message));
            assertThat(rule.clearFlagged(message)).isEmpty();
            assertThat(row.path("humanReviewStatus").asText()).isEqualTo("PENDING");
        }
    }

    @Test
    void 제안라벨은_기존Validator를_통과하고_유형별10건_및_MEDIUM_HIGH경계를_포함한다() throws IOException {
        // given
        var counts = new java.util.HashMap<String, Integer>();
        // when / then
        for (var row : read().path("cases")) {
            var label = row.path("proposedGroundTruth");
            Set<ModerationCategory> categories = new HashSet<>();
            for (var category : label.path("categories")) categories.add(ModerationCategory.valueOf(category.asText()));
            var result = new ModerationResult(ModerationResultType.valueOf(label.path("result").asText()),
                    categories, RiskLevel.valueOf(label.path("riskLevel").asText()));
            ModerationResultValidationSupport.validate(result);
            counts.merge(row.path("group").asText() + ":" + result.riskLevel(), 1, Integer::sum);
        }
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "SAFE:LOW", 10, "PROFANITY:MEDIUM", 5, "PROFANITY:HIGH", 5,
                "SPAM:MEDIUM", 5, "SPAM:HIGH", 5, "PERSONAL_INFORMATION:MEDIUM", 10));
    }

    private static JsonNode read() throws IOException {
        return Issue55MicroBatchEvaluation.JSON.readTree(Files.readString(DRAFT));
    }

    private static String normalized(String message) {
        return Normalizer.normalize(message, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
