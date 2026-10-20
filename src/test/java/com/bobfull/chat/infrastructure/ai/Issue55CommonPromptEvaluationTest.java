package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import static com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.*;
import com.bobfull.chat.infrastructure.ai.Issue55CommonPromptEvaluation.*;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.*;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;

class Issue55CommonPromptEvaluationTest {
    @Test
    void Q1은_승인4문장만_추가하고_Policy_Fewshot15_Output규칙을_보존한다() {
        // given
        String restored = Issue55CommonPromptEvaluation.Q1;
        // when
        for (var addition : Issue55CommonPromptEvaluation.ADDITIONS) {
            assertThat(restored.indexOf(addition.sentence())).isEqualTo(restored.lastIndexOf(addition.sentence())).isNotNegative();
            restored = restored.replace("\n" + addition.sentence(), "");
        }
        // then
        assertThat(restored).isEqualTo(ModerationPrompt.SYSTEM_PROMPT);
        assertThat(Issue55CommonPromptEvaluation.ADDITIONS).hasSize(4);
        assertThat(Issue55CommonPromptEvaluation.Q1).hasSize(1743).doesNotContain("HOLDOUT-", "Item-wise policy checklist");
        String fewshot = Issue55CommonPromptEvaluation.Q1.split("\\[Few-shot boundary examples\\]")[1].split("불완전한 짧은 조각")[0];
        assertThat(fewshot.lines().filter(l -> l.contains("→")).count()).isEqualTo(15);
        assertThat(Issue55CommonPromptEvaluation.system(Condition.A)).isEqualTo(ModerationPrompt.SYSTEM_PROMPT);
        assertThat(Issue55CommonPromptEvaluation.system(Condition.C)).isEqualTo(SYSTEM_PROMPT);
        assertThat(Issue55CommonPromptEvaluation.system(Condition.D)).isEqualTo(Issue55CommonPromptEvaluation.Q1 + BATCH_INSTRUCTIONS);
    }

    @Test
    void 진단20건_동일순서_GT_구성으로_각조건3회_총132호출만_계획한다() {
        // given / when
        var steps = Issue55CommonPromptEvaluation.schedule();
        // then
        assertThat(steps).hasSize(132);
        for (var condition : Condition.values()) {
            assertThat(steps.stream().filter(s -> s.condition() == condition).count()).isEqualTo(condition.batch ? 6 : 60);
            for (int repeat = 1; repeat <= 3; repeat++) {
                final int r = repeat;
                var selected = steps.stream().filter(s -> s.condition() == condition && s.repeat() == r).toList();
                if (condition.batch) assertThat(selected.stream().map(Step::groupIndex)).containsExactly(0, 1);
                else assertThat(selected.stream().map(Step::targetIndex)).containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, 20).boxed().toList());
            }
        }
        assertThat(Issue55MicroBatchF0.targets()).hasSize(20);
        var forecast = Issue55CommonPromptEvaluation.forecast();
        assertThat(forecast).hasSize(4);
        assertThat(.12810065 + forecast.stream().mapToDouble(Forecast::fourAttemptsUsd).sum()).isLessThan(.5);
        System.out.println("Q1_LOCAL_TOKENIZER_FORECAST " + forecast);
    }

    @Test
    void Fake132호출은_반복wire동일_ID역순매핑_및_BatchUsage중복방지를_검증한다() throws IOException {
        // given
        var hashes = new HashMap<String, String>();
        var calls = new ArrayList<BatchObservation>();
        try (var fake = new FakeProvider()) {
            for (var step : Issue55CommonPromptEvaluation.schedule()) {
                fake.enqueue(response(step));
                // when
                var result = Issue55CommonPromptEvaluation.evaluate(fake.client, step);
                String hash = Issue55CommonPromptEvaluation.verifyWire(step, fake.requests.remove());
                // then
                assertThat(hash).isEqualTo(hashes.computeIfAbsent(Issue55CommonPromptEvaluation.cell(step), ignored -> hash));
                assertThat(result.failure()).isNull();
                assertThat(result.cases()).allSatisfy(c -> assertThat(c.actual()).isEqualTo(c.expected()));
                calls.add(result);
            }
        }
        assertThat(hashes).hasSize(44);
        assertThat(calls.stream().flatMap(c -> c.cases().stream())).hasSize(240);
        assertThat(calls.stream().mapToLong(c -> c.usage().total()).sum()).isEqualTo(132 * 120);
    }

    @Test
    void A_C는_원래요청과_동일하고_B_D는_System외_차이가_없다() throws IOException {
        // given
        var target = Issue55MicroBatchF0.targets().get(0);
        var batch = Issue55MicroBatchF0.batches(10).get(0);
        try (var fake = new FakeProvider()) {
            for (var condition : Condition.values()) {
                var step = new Step(1, 0, condition, condition.batch ? -1 : 0);
                fake.enqueue(response(step));
                fake.enqueue(response(step));
                // when
                if (condition.batch) Issue55MicroBatchEvaluation.evaluate(fake.client, batch);
                else Issue55MicroBatchComparison.single(fake.client, target, 1);
                Issue55CommonPromptEvaluation.evaluate(fake.client, step);
                var original = JSON.readTree(fake.requests.remove());
                var current = JSON.readTree(fake.requests.remove());
                // then
                ((tools.jackson.databind.node.ObjectNode) current.path("messages").path(0))
                        .put("content", condition.batch ? SYSTEM_PROMPT : ModerationPrompt.SYSTEM_PROMPT);
                assertThat(current).isEqualTo(original);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "validation", "truncated", "missing", "duplicate", "unknown"})
    void Batch의_계약실패는_정답이_아닌_UNRESOLVED로_보존한다(String mode) throws IOException {
        // given
        var step = new Step(1, 0, Condition.D, -1);
        var batch = Issue55MicroBatchF0.batches(10).get(0);
        var rows = rows(batch);
        Failure expected = Failure.MAPPING;
        String finish = "stop";
        switch (mode) {
            case "missing" -> rows.remove(0);
            case "duplicate" -> rows.set(0, rows.get(1));
            case "unknown" -> rows.set(0, new OutputItem("m999", rows.get(0).moderation()));
            case "validation" -> {
                rows.set(0, new OutputItem(rows.get(0).id(), new ModerationResult(ModerationResultType.SAFE,
                        Set.of(ModerationCategory.SPAM), RiskLevel.LOW)));
                expected = Failure.VALIDATION;
            }
            case "parse" -> expected = Failure.PARSE;
            case "truncated" -> { expected = Failure.TRUNCATED; finish = "length"; }
            default -> throw new IllegalArgumentException();
        }
        try (var fake = new FakeProvider()) {
            fake.enqueue(mode.equals("parse") ? "{" : json(new BatchOutput(rows)), finish);
            // when
            var result = Issue55CommonPromptEvaluation.evaluate(fake.client, step);
            // then
            assertThat(result.failure()).isEqualTo(expected);
            assertThat(result.cases()).anySatisfy(c -> assertThat(c.actual()).isNull());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "validation", "truncated"})
    void Native도_같은_실패계약을_유지한다(String mode) throws IOException {
        // given
        var step = new Step(1, 0, Condition.B, 0);
        String content = mode.equals("parse") ? "{" : mode.equals("validation")
                ? json(new ModerationResult(ModerationResultType.SAFE, Set.of(ModerationCategory.SPAM), RiskLevel.LOW)) : response(step);
        try (var fake = new FakeProvider()) {
            fake.enqueue(content, mode.equals("truncated") ? "length" : "stop");
            // when
            var result = Issue55CommonPromptEvaluation.evaluate(fake.client, step);
            // then
            assertThat(result.failure()).isEqualTo(mode.equals("parse") ? Failure.PARSE
                    : mode.equals("validation") ? Failure.VALIDATION : Failure.TRUNCATED);
            assertThat(result.cases().get(0).actual()).isNull();
        }
    }

    private static String response(Step step) {
        if (!step.condition().batch) return json(Issue55MicroBatchEvaluation.expected(
                Issue55MicroBatchF0.targets().get(step.targetIndex()).testCase()));
        var values = rows(Issue55MicroBatchF0.batches(10).get(step.groupIndex()));
        Collections.reverse(values);
        return json(new BatchOutput(values));
    }

    private static ArrayList<OutputItem> rows(Batch batch) {
        return new ArrayList<>(batch.targets().stream().map(t -> new OutputItem(t.neutralId(),
                Issue55MicroBatchEvaluation.expected(t.testCase()))).toList());
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
        private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();

        private FakeProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String reply = replies.remove();
                byte[] body = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            sdk = OpenAiSetup.setupSyncClient(url, "fake-cross-key", null, null, null, null, false, false,
                    Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(), ObservationRegistry.NOOP, null, List.of());
            async = OpenAiSetup.setupAsyncClient(url, "fake-cross-key", null, null, null, null, false, false,
                    Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(), ObservationRegistry.NOOP, null, List.of());
            client = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sdk).openAiClientAsync(async)
                    .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).build()).build()).build();
        }

        private void enqueue(String content) {
            enqueue(content, "stop");
        }

        private void enqueue(String content, String finish) {
            replies.add(json(Map.of("id", "chatcmpl-fake-cross", "object", "chat.completion", "created", 1720000000,
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
