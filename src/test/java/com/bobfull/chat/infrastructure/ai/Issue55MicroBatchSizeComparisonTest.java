package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchOutput;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchUsage;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.CaseObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Failure;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.OutputItem;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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

class Issue55MicroBatchSizeComparisonTest {
    @Test
    void 크기별_분할은_F0_20건의_ID_GT_순서를_그대로_유지한다() {
        // given / when / then
        for (int size : Issue55MicroBatchSizeComparison.SIZES) {
            var batches = Issue55MicroBatchSizeComparison.batches(size);
            assertThat(batches).hasSize(20 / size);
            assertThat(batches.stream().flatMap(b -> b.targets().stream()).toList()).isEqualTo(Issue55MicroBatchF0.targets());
            assertThat(batches).allSatisfy(b -> {
                assertThat(b.targets()).hasSize(size);
                assertThat(b.outputCap()).isEqualTo(128 * size);
            });
            if (size == 5 || size == 10) {
                assertThat(batches.stream().map(b -> b.input()).toList())
                        .isEqualTo(Issue55MicroBatchF0.batches(size).stream().map(b -> b.input()).toList());
            }
        }
    }

    @Test
    void 교차계획은_각크기2회_34호출이며_그룹과_그룹내순서를_바꾸지_않는다() {
        // given / when
        var schedule = Issue55MicroBatchSizeComparison.schedule();
        // then
        assertThat(schedule).hasSize(34).doesNotHaveDuplicates();
        for (int repeat = 1; repeat <= 2; repeat++) {
            int r = repeat;
            assertThat(schedule.stream().filter(s -> s.repeat() == r).count()).isEqualTo(17);
            for (int size : Issue55MicroBatchSizeComparison.SIZES) {
                assertThat(schedule.stream().filter(s -> s.repeat() == r && s.batchSize() == size).map(s -> s.batchIndex()).toList())
                        .isEqualTo(java.util.stream.IntStream.range(0, 20 / size).boxed().toList());
            }
        }
        assertThat(schedule.stream().limit(4).map(s -> s.batchSize()).toList()).containsExactly(2, 5, 10, 20);
        assertThat(schedule.stream().skip(17).limit(4).map(s -> s.batchSize()).toList()).containsExactly(20, 10, 5, 2);
    }

    @Test
    void 사전추정은_현재누적액과_34호출_전체OutputCap을_사용한다() {
        // given / when
        var forecast = Issue55MicroBatchSizeComparison.forecast();
        // then
        assertThat(forecast.stream().mapToInt(f -> f.logicalCalls()).sum()).isEqualTo(34);
        assertThat(forecast.stream().mapToLong(f -> f.outputCapSum()).sum()).isEqualTo(20_480);
        assertThat(Issue55MicroBatchSizeComparison.PRIOR_ESTIMATED_USD
                + forecast.stream().mapToDouble(f -> f.allFourAttemptsEstimateUsd()).sum()).isLessThan(.50);
        forecast.forEach(f -> System.out.println("SIZE_B_LOCAL_TOKENIZER_FORECAST " + f));
    }

    @Test
    void Batch20의_긴_유효출력_tokenizer추정은_cap2560보다_작다() {
        // given: 모든 category/HIGH와 pretty JSON을 사용한 사전 점검이며 Provider Usage가 아니다.
        var longResult = new ModerationResult(ModerationResultType.FLAGGED,
                Set.of(ModerationCategory.PROFANITY, ModerationCategory.PERSONAL_INFORMATION, ModerationCategory.SPAM), RiskLevel.HIGH);
        var batch = Issue55MicroBatchSizeComparison.batches(20).get(0);
        var output = new BatchOutput(batch.targets().stream().map(t -> new OutputItem(t.neutralId(), longResult)).toList());
        // when
        String pretty = Issue55MicroBatchEvaluation.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        int estimate = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE).countTokens(pretty);
        // then
        assertThat(batch.outputCap()).isEqualTo(2560);
        assertThat(estimate).isLessThan(batch.outputCap());
        System.out.printf("BATCH20_OUTPUT_CAP estimatedTokens=%d cap=%d (local estimate, not Provider Usage)%n", estimate, batch.outputCap());
    }

    @Test
    void Batch20은_Fake_HTTP에서_동일Schema와_cap을_전송하고_역순응답을_ID로_복원한다() throws IOException {
        // given
        var batch = Issue55MicroBatchSizeComparison.batches(20).get(0);
        var rows = new ArrayList<>(batch.targets().stream().map(t ->
                new OutputItem(t.neutralId(), Issue55MicroBatchEvaluation.expected(t.testCase()))).toList());
        Collections.reverse(rows);
        try (var fake = new FakeProvider()) {
            fake.enqueue(json(new BatchOutput(rows)), "stop");
            // when
            var response = Issue55MicroBatchEvaluation.evaluate(fake.client, batch);
            // then
            assertThat(response.failure()).isNull();
            assertThat(response.cases()).hasSize(20).allSatisfy(c -> assertThat(c.actual()).isEqualTo(c.expected()));
            assertThat(response.cases().stream().map(c -> c.caseId()).toList()).isEqualTo(Issue55MicroBatchF0.IDS);
            var request = fake.requests.peek();
            assertThat(request.path("max_tokens").asInt()).isEqualTo(2560);
            assertThat(request.path("model").asText()).isEqualTo(Issue55MicroBatchEvaluation.MODEL);
            assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(Issue55MicroBatchEvaluation.SYSTEM_PROMPT);
            var input = Issue55MicroBatchEvaluation.JSON.readTree(request.path("messages").get(1).path("content").asText());
            assertThat(input).isEqualTo(Issue55MicroBatchEvaluation.JSON.readTree(json(batch.input())));
            assertThat(input.path("items").size()).isEqualTo(20);
            assertThat(input.toString()).doesNotContain("HOLDOUT-", "expected", "caseType");
            assertThat(request.path("response_format").path("json_schema").path("schema"))
                    .isEqualTo(Issue55MicroBatchEvaluation.JSON.readTree(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()));
            assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
            assertThat(response.usage().total()).isEqualTo(120);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "unexpected", "parse", "validation", "length"})
    void Batch20의_불완전응답을_정상판정으로_바꾸지_않는다(String scenario) throws IOException {
        // given
        var batch = Issue55MicroBatchSizeComparison.batches(20).get(0);
        var rows = new ArrayList<>(batch.targets().stream().map(t ->
                new OutputItem(t.neutralId(), Issue55MicroBatchEvaluation.expected(t.testCase()))).toList());
        switch (scenario) {
            case "missing" -> rows.remove(rows.size() - 1);
            case "duplicate" -> rows.add(rows.get(0));
            case "unexpected" -> rows.set(0, new OutputItem("m999", rows.get(0).moderation()));
            case "validation" -> rows.set(0, new OutputItem(rows.get(0).id(),
                    new ModerationResult(ModerationResultType.SAFE, Set.of(ModerationCategory.SPAM), RiskLevel.LOW)));
            default -> { }
        }
        try (var fake = new FakeProvider()) {
            fake.enqueue(scenario.equals("parse") ? "not-json" : json(new BatchOutput(rows)),
                    scenario.equals("length") ? "length" : "stop");
            // when
            var result = Issue55MicroBatchEvaluation.evaluate(fake.client, batch);
            // then
            assertThat(result.failure()).isEqualTo(switch (scenario) {
                case "parse" -> Failure.PARSE;
                case "validation" -> Failure.VALIDATION;
                case "length" -> Failure.TRUNCATED;
                default -> Failure.MAPPING;
            });
            assertThat(result.cases()).hasSize(20);
            assertThat(result.cases().get(0).actual()).isNull();
            assertThat(result.usage().total()).isEqualTo(120);
            if (scenario.equals("validation")) {
                assertThat(result.cases().subList(1, 20)).allSatisfy(c -> assertThat(c.actual()).isNotNull());
            } else {
                assertThat(result.cases()).allSatisfy(c -> assertThat(c.actual()).isNull());
            }
        }
    }

    @Test
    void Fake_전체34호출은_160판정과_8그룹을_복원하고_Usage를_한번만_합산한다() throws IOException {
        // given: 정답 Fake는 계약 검증용이며 모델 품질 결과가 아니다.
        List<Issue55MicroBatchSizeComparison.Call> calls = new ArrayList<>();
        try (var fake = new FakeProvider()) {
            // when
            for (var step : Issue55MicroBatchSizeComparison.schedule()) {
                var batch = Issue55MicroBatchSizeComparison.batches(step.batchSize()).get(step.batchIndex());
                fake.enqueue(json(new BatchOutput(batch.targets().stream().map(t ->
                        new OutputItem(t.neutralId(), Issue55MicroBatchEvaluation.expected(t.testCase()))).toList())), "stop");
                var response = Issue55MicroBatchEvaluation.evaluate(fake.client, batch);
                assertThat(response.failure()).isNull();
                calls.add(new Issue55MicroBatchSizeComparison.Call(step, Issue55MicroBatchSizeComparison.requestHash(batch),
                        "fake", 1_000_000, 1, response));
            }
            // then
            assertThat(fake.requests).hasSize(34);
            assertThat(Issue55MicroBatchSizeComparison.groups(calls)).hasSize(8).allSatisfy(g -> {
                assertThat(g.stats().quality().count()).isEqualTo(20);
                assertThat(g.stats().quality().resultMatches()).isEqualTo(20);
                assertThat(g.stats().inputTokens()).isEqualTo(100 * (20 / g.batchSize()));
                assertThat(g.stats().outputTokens()).isEqualTo(20 * (20 / g.batchSize()));
            });
            assertThat(Issue55MicroBatchSizeComparison.series(calls)).hasSize(80).allSatisfy(s -> assertThat(s.changed()).isFalse());
            assertThat(Issue55MicroBatchSizeComparison.comparisons(calls)).hasSize(12).allSatisfy(c -> assertThat(c.changes()).isEmpty());
            var wire = new ArrayList<>(fake.requests);
            for (var request : wire) {
                for (String field : List.of("model", "temperature", "top_p", "seed", "frequency_penalty", "presence_penalty", "stream", "n")) {
                    assertThat(request.path(field)).isEqualTo(wire.get(0).path(field));
                }
            }
        }
    }

    @Test
    void 크기간_새FN_HIGH하향과_같은크기_반복변동을_별개로_집계한다() {
        // given
        var safe = new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
        var medium = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM);
        List<Issue55MicroBatchSizeComparison.Call> calls = new ArrayList<>();
        for (var step : Issue55MicroBatchSizeComparison.schedule()) {
            var batch = Issue55MicroBatchSizeComparison.batches(step.batchSize()).get(step.batchIndex());
            var rows = batch.targets().stream().map(t -> {
                var expected = Issue55MicroBatchEvaluation.expected(t.testCase());
                ModerationResult actual = expected;
                if (t.testCase().id().equals("HOLDOUT-PROFANITY-16") && step.batchSize() == 5) {
                    actual = safe;
                }
                if (t.testCase().id().equals("HOLDOUT-PROFANITY-15") && step.batchSize() == 10) {
                    actual = medium;
                }
                if (t.testCase().id().equals("HOLDOUT-SAFE-17") && step.batchSize() == 20 && step.repeat() == 2) {
                    actual = medium;
                }
                return new CaseObservation(t.testCase().id(), batch.id(), expected, actual, null);
            }).toList();
            var response = new BatchObservation(batch.id(), step.batchSize(), batch.outputCap(), new BatchUsage(100, 20, 120, 0L),
                    "fake", 1_000_000, 1_000_000, null, null, rows);
            calls.add(new Issue55MicroBatchSizeComparison.Call(step, "fake", "fake", 1_000_000, 1, response));
        }
        // when
        var comparisons = Issue55MicroBatchSizeComparison.comparisons(calls);
        // then
        assertThat(comparisons.stream().filter(c -> c.referenceSize() == 2 && c.comparedSize() == 5)
                .flatMap(c -> c.changes().stream()).filter(c -> c.newFn()).count()).isEqualTo(2);
        assertThat(comparisons.stream().filter(c -> c.referenceSize() == 2 && c.comparedSize() == 10)
                .flatMap(c -> c.gtHighDowngradeIds().stream()).toList()).containsExactly("HOLDOUT-PROFANITY-15", "HOLDOUT-PROFANITY-15");
        assertThat(Issue55MicroBatchSizeComparison.series(calls).stream().filter(s -> s.changed()).map(s -> s.caseId()).toList())
                .containsExactly("HOLDOUT-SAFE-17");
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
            sdk = OpenAiSetup.setupSyncClient(url, "fake-size-b-key", null, null, null, null, false, false,
                    Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(), ObservationRegistry.NOOP, null, List.of());
            async = OpenAiSetup.setupAsyncClient(url, "fake-size-b-key", null, null, null, null, false, false,
                    Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(), ObservationRegistry.NOOP, null, List.of());
            client = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sdk).openAiClientAsync(async)
                    .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).build()).build()).build();
        }

        private void enqueue(String content, String finish) {
            replies.add(json(Map.of("id", "chatcmpl-fake-size-b", "object", "chat.completion", "created", 1720000000,
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
