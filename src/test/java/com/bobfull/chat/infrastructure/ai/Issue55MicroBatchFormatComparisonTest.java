package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchOutput;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchUsage;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.CaseObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Failure;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.OutputItem;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatComparison.Judgment;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchFormatComparison.Mode;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import tools.jackson.databind.JsonNode;

class Issue55MicroBatchFormatComparisonTest {
    @Test
    void 고정20건의_인접쌍_순서를_R2에서_뒤집고_총80회만_계획한다() {
        // given / when
        var schedule = Issue55MicroBatchFormatComparison.schedule();
        var targets = Issue55MicroBatchF0.targets();
        // then
        assertThat(schedule).hasSize(80);
        assertThat(targets.stream().map(t -> t.testCase().id()).toList()).isEqualTo(Issue55MicroBatchF0.IDS);
        for (int i = 0; i < 20; i++) {
            var r1 = schedule.subList(i * 2, i * 2 + 2);
            var r2 = schedule.subList(40 + i * 2, 42 + i * 2);
            assertThat(r1).allSatisfy(s -> assertThat(s.repeat()).isEqualTo(1));
            assertThat(r2).allSatisfy(s -> assertThat(s.repeat()).isEqualTo(2));
            assertThat(r1.stream().map(s -> s.mode()).toList()).containsExactlyInAnyOrder(Mode.NATIVE, Mode.BATCH1);
            assertThat(r1.get(0).targetIndex()).isEqualTo(i);
            assertThat(r1.get(1).targetIndex()).isEqualTo(i);
            assertThat(r2.get(0).targetIndex()).isEqualTo(i);
            assertThat(r2.get(1).targetIndex()).isEqualTo(i);
            assertThat(r1.get(0).mode()).isEqualTo(r2.get(1).mode());
            assertThat(r1.get(1).mode()).isEqualTo(r2.get(0).mode());
            var t = targets.get(i);
            assertThat(Issue55MicroBatchFormatComparison.batch1(t).outputCap()).isEqualTo(128);
            assertThat(Issue55MicroBatchFormatComparison.requestHash(Mode.BATCH1, t))
                    .isEqualTo(Issue55MicroBatchFormatComparison.requestHash(Mode.BATCH1, t))
                    .isNotEqualTo(Issue55MicroBatchFormatComparison.requestHash(Mode.NATIVE, t));
        }
    }

    @Test
    void Full_tuple_정답과_Result_Category_정답을_구분하고_미판정은_오답으로_대체하지_않는다() {
        // given
        var safe = new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
        var high = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.HIGH);
        var medium = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        // when / then
        assertThat(Issue55MicroBatchFormatComparison.judgment(high, high, high, true)).isEqualTo(Judgment.BOTH_CORRECT);
        assertThat(Issue55MicroBatchFormatComparison.judgment(high, high, medium, true)).isEqualTo(Judgment.NATIVE_ONLY_CORRECT);
        assertThat(Issue55MicroBatchFormatComparison.judgment(high, medium, high, true)).isEqualTo(Judgment.BATCH1_ONLY_CORRECT);
        assertThat(Issue55MicroBatchFormatComparison.judgment(high, medium, medium, true)).isEqualTo(Judgment.BOTH_WRONG);
        assertThat(Issue55MicroBatchFormatComparison.judgment(high, medium, medium, false)).isEqualTo(Judgment.BOTH_CORRECT);
        assertThat(Issue55MicroBatchFormatComparison.judgment(high, high, safe, false)).isEqualTo(Judgment.NATIVE_ONLY_CORRECT);
        assertThat(Issue55MicroBatchFormatComparison.judgment(safe, null, safe, true)).isEqualTo(Judgment.UNRESOLVED);
    }

    @Test
    void 무과금_사전추정은_80회와_현재_누적비용을_사용한다() {
        // given / when
        var forecast = Issue55MicroBatchFormatComparison.forecast();
        // then
        assertThat(forecast.stream().mapToInt(f -> f.logicalCalls()).sum()).isEqualTo(80);
        assertThat(forecast.stream().mapToLong(f -> f.outputCapSum()).sum()).isEqualTo(10_240);
        assertThat(Issue55MicroBatchFormatComparison.PRIOR_ESTIMATED_USD
                + forecast.stream().mapToDouble(f -> f.allFourAttemptsEstimateUsd()).sum()).isLessThan(.50);
        forecast.forEach(f -> System.out.println("FORMAT_A_LOCAL_TOKENIZER_FORECAST " + f));
    }

    @Test
    void 같은반복_Native대비_새FP_FN_HIGH하향과_조건별_반복변동을_분리한다() {
        // given
        var safe = new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
        var high = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.HIGH);
        var medium = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        var targets = Issue55MicroBatchF0.targets();
        var calls = new ArrayList<Issue55MicroBatchFormatComparison.Call>();
        for (var step : Issue55MicroBatchFormatComparison.schedule()) {
            var target = targets.get(step.targetIndex());
            var expected = Issue55MicroBatchEvaluation.expected(target.testCase());
            ModerationResult actual = expected;
            if (target.testCase().id().equals("HOLDOUT-SAFE-17") && step.mode() == Mode.BATCH1 && step.repeat() == 1) {
                actual = medium;
            }
            if (target.testCase().id().equals("HOLDOUT-PROFANITY-16")) {
                actual = step.mode() == Mode.NATIVE ? high : step.repeat() == 1 ? safe : medium;
            }
            if (target.testCase().id().equals("HOLDOUT-PROFANITY-15")) {
                actual = step.mode() == Mode.NATIVE && step.repeat() == 1 ? medium : high;
            }
            var row = new CaseObservation(target.testCase().id(), "fake", expected, actual, null);
            var response = new BatchObservation("fake", 1, 128, new BatchUsage(100, 20, 120, 0L), "fake",
                    1_000_000, 1_000_000, null, null, List.of(row));
            calls.add(new Issue55MicroBatchFormatComparison.Call(step, target.testCase().id(), "fake", "fake", 1_000_000, 1, response));
        }
        // when
        var pairs = Issue55MicroBatchFormatComparison.pairs(calls);
        var series = Issue55MicroBatchFormatComparison.series(calls);
        // then
        assertThat(pairs.stream().filter(p -> p.newFp()).map(p -> p.caseId()).toList()).containsExactly("HOLDOUT-SAFE-17");
        assertThat(pairs.stream().filter(p -> p.newFn()).map(p -> p.caseId()).toList()).containsExactly("HOLDOUT-PROFANITY-16");
        assertThat(pairs.stream().filter(p -> p.gtHighDowngradeFromNative()).count()).isEqualTo(2);
        assertThat(series.stream().filter(s -> s.nativeChanged()).map(s -> s.caseId()).toList()).containsExactly("HOLDOUT-PROFANITY-15");
        assertThat(series.stream().filter(s -> s.batch1Changed()).map(s -> s.caseId()).toList())
                .containsExactly("HOLDOUT-SAFE-17", "HOLDOUT-PROFANITY-16");
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing", "duplicate", "unexpected", "parse", "validation", "length"})
    void Batch1은_실제SDK와_Fake_HTTP에서_계약을_검증하고_실패에도_Usage를_남긴다(String scenario) throws IOException {
        // given
        var target = Issue55MicroBatchF0.targets().get(0);
        var expected = Issue55MicroBatchEvaluation.expected(target.testCase());
        var item = new OutputItem(target.neutralId(), expected);
        String content = switch (scenario) {
            case "parse" -> "not-json";
            case "missing" -> json(new BatchOutput(List.of()));
            case "duplicate" -> json(new BatchOutput(List.of(item, item)));
            case "unexpected" -> json(new BatchOutput(List.of(new OutputItem("m999", expected))));
            case "validation" -> json(new BatchOutput(List.of(new OutputItem(target.neutralId(),
                    new ModerationResult(ModerationResultType.SAFE, Set.of(ModerationCategory.SPAM), RiskLevel.LOW)))));
            default -> json(new BatchOutput(List.of(item)));
        };
        try (var fake = new FakeProvider()) {
            fake.enqueue(content, scenario.equals("length") ? "length" : "stop");
            // when
            var result = Issue55MicroBatchFormatComparison.evaluate(fake.client, Mode.BATCH1, target, 0);
            // then
            assertThat(result.inputCount()).isEqualTo(1);
            assertThat(result.outputCap()).isEqualTo(128);
            assertThat(result.usage().total()).isEqualTo(120);
            assertThat(result.cases()).hasSize(1);
            var request = fake.requests.peek();
            assertThat(request.path("model").asText()).isEqualTo(Issue55MicroBatchEvaluation.MODEL);
            assertThat(request.path("max_tokens").asInt()).isEqualTo(128);
            assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(Issue55MicroBatchEvaluation.SYSTEM_PROMPT);
            var input = Issue55MicroBatchEvaluation.JSON.readTree(request.path("messages").get(1).path("content").asText());
            assertThat(input.path("items").size()).isEqualTo(1);
            assertThat(input.path("items").get(0).path("id").asText()).isEqualTo(target.neutralId());
            assertThat(input.path("items").get(0).path("content").asText()).isEqualTo(target.testCase().message());
            assertThat(input.toString()).doesNotContain("HOLDOUT-", "expected", "caseType");
            assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
            assertThat(request.path("response_format").path("json_schema").path("schema"))
                    .isEqualTo(Issue55MicroBatchEvaluation.JSON.readTree(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()));
            if (scenario.equals("valid")) {
                assertThat(result.failure()).isNull();
                assertThat(result.cases().get(0).actual()).isEqualTo(expected);
            } else {
                assertThat(result.failure()).isEqualTo(switch (scenario) {
                    case "parse" -> Failure.PARSE;
                    case "validation" -> Failure.VALIDATION;
                    case "length" -> Failure.TRUNCATED;
                    default -> Failure.MAPPING;
                });
                assertThat(result.cases().get(0).actual()).isNull();
            }
        }
    }

    @Test
    void Fake_80회는_각20건씩_집계하며_Batch1_Usage를_중복시키지_않는다() throws IOException {
        // given: 정답을 반환하는 Fake다. 실제 모델 품질 결과로 해석하지 않는다.
        var targets = Issue55MicroBatchF0.targets();
        List<Issue55MicroBatchFormatComparison.Call> calls = new ArrayList<>();
        try (var fake = new FakeProvider()) {
            // when
            for (var step : Issue55MicroBatchFormatComparison.schedule()) {
                var target = targets.get(step.targetIndex());
                var expected = Issue55MicroBatchEvaluation.expected(target.testCase());
                fake.enqueue(step.mode() == Mode.NATIVE ? json(expected)
                        : json(new BatchOutput(List.of(new OutputItem(target.neutralId(), expected)))), "stop");
                var response = Issue55MicroBatchFormatComparison.evaluate(fake.client, step.mode(), target, step.targetIndex());
                assertThat(response.failure()).isNull();
                calls.add(new Issue55MicroBatchFormatComparison.Call(step, target.testCase().id(),
                        Issue55MicroBatchFormatComparison.requestHash(step.mode(), target), "fake", 1_000_000, 1, response));
            }
            // then
            assertThat(fake.requests).hasSize(80);
            var wire = new ArrayList<>(fake.requests);
            for (int i = 0; i < wire.size(); i += 2) {
                for (String field : List.of("model", "max_tokens", "temperature", "top_p", "seed",
                        "frequency_penalty", "presence_penalty", "stream", "n")) {
                    assertThat(wire.get(i).path(field)).isEqualTo(wire.get(i + 1).path(field));
                }
            }
            assertThat(Issue55MicroBatchFormatComparison.pairs(calls)).hasSize(40).allSatisfy(p -> {
                assertThat(p.fullEqual()).isTrue();
                assertThat(p.fullJudgment()).isEqualTo(Judgment.BOTH_CORRECT);
            });
            assertThat(Issue55MicroBatchFormatComparison.series(calls)).hasSize(20).allSatisfy(s -> {
                assertThat(s.nativeChanged()).isFalse();
                assertThat(s.batch1Changed()).isFalse();
            });
            assertThat(Issue55MicroBatchFormatComparison.groups(calls)).hasSize(4).allSatisfy(g -> {
                assertThat(g.stats().quality().count()).isEqualTo(20);
                assertThat(g.stats().quality().resultMatches()).isEqualTo(20);
                assertThat(g.stats().inputTokens()).isEqualTo(2_000);
                assertThat(g.stats().outputTokens()).isEqualTo(400);
                assertThat(g.stats().totalTokens()).isEqualTo(2_400);
            });
        }
    }

    private static String json(Object value) {
        return Issue55MicroBatchEvaluation.JSON.writeValueAsString(value);
    }

    private static final class FakeProvider implements AutoCloseable {
        private final HttpServer server;
        private final OpenAIClient sdk;
        private final OpenAIClientAsync async;
        private final ChatClient client;
        private final ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<JsonNode> requests = new ConcurrentLinkedQueue<>();

        private FakeProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.add(Issue55MicroBatchEvaluation.JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                var reply = replies.poll();
                byte[] body = (reply == null ? "{}" : reply).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply == null ? 400 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            sdk = OpenAiSetup.setupSyncClient(url, "fake-format-a-key", null, null, null, null, false, false,
                    Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(), ObservationRegistry.NOOP, null, List.of());
            async = OpenAiSetup.setupAsyncClient(url, "fake-format-a-key", null, null, null, null, false, false,
                    Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(), ObservationRegistry.NOOP, null, List.of());
            client = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sdk).openAiClientAsync(async)
                    .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).build()).build()).build();
        }

        private void enqueue(String content, String finish) {
            replies.add(json(Map.of("id", "chatcmpl-fake-format-a", "object", "chat.completion", "created", 1720000000,
                    "model", "gpt-4o-mini-fake", "choices", List.of(Map.of("index", 0, "finish_reason", finish,
                            "message", Map.of("role", "assistant", "content", content))),
                    "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120,
                            "prompt_tokens_details", Map.of("cached_tokens", 0)))));
        }

        @Override
        public void close() {
            sdk.close();
            async.close();
            server.stop(0);
        }
    }
}
