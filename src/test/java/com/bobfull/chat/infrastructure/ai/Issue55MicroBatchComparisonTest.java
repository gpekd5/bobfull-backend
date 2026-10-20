package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchUsage;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.CaseObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Failure;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class Issue55MicroBatchComparisonTest {
    @Test
    void 사전_예산은_143회와_출력cap을_계산하며_Provider_사용량으로_표현하지_않는다() {
        // given / when
        var forecast = Issue55MicroBatchComparison.forecast();
        // then
        assertThat(forecast.stream().mapToInt(Issue55MicroBatchComparison.Forecast::logicalCalls).sum()).isEqualTo(143);
        assertThat(forecast.stream().mapToLong(Issue55MicroBatchComparison.Forecast::outputCapSum).sum()).isEqualTo(40_448);
        assertThat(forecast).allSatisfy(f -> {
            assertThat(f.estimatedInputTokens()).isPositive();
            assertThat(f.allFourAttemptsEstimateUsd()).isEqualTo(4 * f.noCacheEstimateAtCapUsd());
            System.out.println("LOCAL_TOKENIZER_FORECAST " + f);
        });
        assertThat(Issue55MicroBatchComparison.PRIOR_ESTIMATED_USD
                + forecast.stream().mapToDouble(Issue55MicroBatchComparison.Forecast::allFourAttemptsEstimateUsd).sum())
                .isLessThan(Issue55MicroBatchComparison.LIMIT_USD);
    }

    @Test
    void 새_FP_FN_위험도_변경은_과거_Before가_아닌_이번_단건과_비교한다() {
        // given: SAFE-17은 역사적 Before에서 오탐이었지만 이번 단건은 SAFE로 둔다.
        var high = result(ModerationResultType.FLAGGED, RiskLevel.HIGH);
        var medium = result(ModerationResultType.FLAGGED, RiskLevel.MEDIUM);
        var safe = result(ModerationResultType.SAFE, RiskLevel.LOW);
        var before = List.of(row("HOLDOUT-SAFE-17", safe, safe), row("positive", high, high), row("risk", high, high));
        var after = List.of(row("HOLDOUT-SAFE-17", safe, medium), row("positive", high, safe), row("risk", high, medium));
        // when
        var changes = Issue55MicroBatchComparison.changes(before, after);
        // then
        assertThat(changes).hasSize(3);
        assertThat(changes.get(0).newFp()).isTrue();
        assertThat(changes.get(1).newFn()).isTrue();
        assertThat(changes.get(2).riskRegression()).isTrue();
        assertThat(changes.get(2).resultChanged()).isFalse();
        assertThat(changes.get(2).categoryChanged()).isFalse();
        assertThat(changes.get(2).actionabilityRegression()).isFalse();
    }

    @Test
    void UNRESOLVED는_정답수에_포함하지_않고_Confusion의_TN으로_바꾸지_않는다() {
        // given
        var safe = result(ModerationResultType.SAFE, RiskLevel.LOW);
        var high = result(ModerationResultType.FLAGGED, RiskLevel.HIGH);
        // when
        var quality = Issue55MicroBatchComparison.quality(List.of(row("a", safe, safe), row("b", high, safe),
                new CaseObservation("c", "b001", safe, null, Failure.VALIDATION)));
        // then
        assertThat(quality.count()).isEqualTo(3);
        assertThat(quality.unresolved()).isEqualTo(1);
        assertThat(quality.resultMatches()).isEqualTo(1);
        assertThat(quality.confusion().total()).isEqualTo(2);
        assertThat(quality.confusion().trueNegative()).isEqualTo(1);
        assertThat(quality.confusion().falseNegative()).isEqualTo(1);
    }

    @Test
    void Usage는_Batch당_한번만_합산하고_Cache_할인은_Input에서_차감해_계산한다() {
        // given
        var plan = Issue55MicroBatchEvaluation.plan(2);
        var calls = plan.batches().stream().map(b -> new BatchObservation(b.id(), b.targets().size(), b.outputCap(),
                new BatchUsage(100, 20, 120, 80L), "fake", 1_000_000, 1_000_000, null, null,
                b.targets().stream().map(t -> row(t.testCase().id(), Issue55MicroBatchEvaluation.expected(t.testCase()),
                        Issue55MicroBatchEvaluation.expected(t.testCase()))).toList())).toList();
        // when
        var m = Issue55MicroBatchComparison.metrics(calls, 40_000_000, 40);
        // then
        assertThat(m.all80().resultMatches()).isEqualTo(80);
        assertThat(m.llm79().resultMatches()).isEqualTo(79);
        assertThat(m.inputTokens()).isEqualTo(4_000);
        assertThat(m.outputTokens()).isEqualTo(800);
        assertThat(m.cachedInputTokens()).isEqualTo(3_200);
        assertThat(m.noCacheEstimatedUsd()).isCloseTo(.00108, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(m.cacheAwareEstimatedUsd()).isCloseTo(.00084, org.assertj.core.data.Offset.offset(1e-10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "parse", "validation", "length"})
    void 단건_하네스는_기존_Prompt와_Schema를_사용하고_실패에도_Usage를_보존한다(String scenario) {
        // given
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("gpt-4o-mini").build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            var options = (OpenAiChatOptions) prompt.getOptions();
            assertThat(options.getMaxTokens()).isEqualTo(128);
            assertThat(options.getOutputSchema()).isEqualTo(Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema());
            assertThat(prompt.getInstructions()).hasSize(2);
            assertThat(prompt.getInstructions().get(0).getText()).isEqualTo(ModerationPrompt.SYSTEM_PROMPT);
            String content = "parse".equals(scenario) ? "not-json" : Issue55MicroBatchEvaluation.JSON.writeValueAsString(
                    new ModerationResult(ModerationResultType.SAFE,
                            "validation".equals(scenario) ? Set.of(ModerationCategory.SPAM) : Set.of(), RiskLevel.LOW));
            return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(content),
                            ChatGenerationMetadata.builder().finishReason("length".equals(scenario) ? "length" : "stop").build())))
                    .metadata(ChatResponseMetadata.builder().model("fake").usage(new DefaultUsage(100, 20, 120)).build()).build();
        });
        var target = Issue55MicroBatchEvaluation.plan(2).batches().get(0).targets().get(0);
        // when
        var response = Issue55MicroBatchComparison.single(ChatClient.builder(model).build(), target, 1);
        // then
        assertThat(response.usage().total()).isEqualTo(120);
        if ("valid".equals(scenario)) {
            assertThat(response.failure()).isNull();
            assertThat(response.cases().get(0).actual().result()).isEqualTo(ModerationResultType.SAFE);
        } else {
            assertThat(response.failure()).isEqualTo(switch (scenario) {
                case "parse" -> Failure.PARSE;
                case "length" -> Failure.TRUNCATED;
                default -> Failure.VALIDATION;
            });
            assertThat(response.cases().get(0).actual()).isNull();
        }
    }

    private static ModerationResult result(ModerationResultType type, RiskLevel risk) {
        return new ModerationResult(type, type == ModerationResultType.SAFE ? Set.of() : Set.of(ModerationCategory.PROFANITY), risk);
    }

    private static CaseObservation row(String id, ModerationResult expected, ModerationResult actual) {
        return new CaseObservation(id, "b001", expected, actual, null);
    }
}
