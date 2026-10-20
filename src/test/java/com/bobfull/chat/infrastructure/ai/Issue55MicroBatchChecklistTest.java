package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import static com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.JSON;
import static com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.SYSTEM_PROMPT;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchChecklist.*;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.*;
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

class Issue55MicroBatchChecklistTest {
    @Test
    void Control과_Candidate는_승인된_추가블록만_다르고_20건의_GT와_순서를_유지한다() {
        // given / when / then
        assertThat(Issue55MicroBatchChecklist.system(Arm.CONTROL)).isEqualTo(SYSTEM_PROMPT);
        assertThat(Issue55MicroBatchChecklist.system(Arm.CANDIDATE)).isEqualTo(SYSTEM_PROMPT + Issue55MicroBatchChecklist.CHECKLIST);
        assertThat(Issue55MicroBatchChecklist.CHECKLIST).doesNotContain("HOLDOUT-", "PROFANITY-16", "SAFE-17");
        assertThat(Issue55MicroBatchF0.targets()).hasSize(20);
        var steps = Issue55MicroBatchChecklist.schedule();
        assertThat(steps).hasSize(12);
        for (int run = 1; run <= 3; run++) {
            final int repeat = run;
            for (var arm : Arm.values()) {
                assertThat(steps.stream().filter(s -> s.repeat() == repeat && s.arm() == arm).map(Step::batchIndex))
                        .containsExactly(0, 1);
            }
        }
        var forecast = Issue55MicroBatchChecklist.forecast();
        assertThat(forecast).hasSize(2).allSatisfy(f -> {
            assertThat(f.logicalCalls()).isEqualTo(6);
            assertThat(f.outputCapSum()).isEqualTo(7680);
        });
        double upper = forecast.stream().mapToDouble(Issue55MicroBatchComparison.Forecast::allFourAttemptsEstimateUsd).sum();
        assertThat(.12402005 + upper).isLessThan(.5);
        System.out.println("CHECKLIST_LOCAL_TOKENIZER_FORECAST " + forecast);
    }

    @Test
    void Fake12호출은_동일요청을_반복하고_역순결과를_ID로_매핑하며_Usage를_중복합산하지_않는다() throws IOException {
        // given
        var hashes = new HashMap<String, String>();
        var results = new ArrayList<BatchObservation>();
        try (var fake = new FakeProvider()) {
            for (var step : Issue55MicroBatchChecklist.schedule()) {
                var batch = Issue55MicroBatchF0.batches(10).get(step.batchIndex());
                var rows = rows(batch);
                Collections.reverse(rows);
                fake.enqueue(json(new BatchOutput(rows)));
                // when
                var response = Issue55MicroBatchChecklist.evaluate(fake.client, batch, step.arm());
                String wire = fake.requests.remove();
                String hash = Issue55MicroBatchChecklist.verifyWire(step, wire);
                String key = step.arm() + ":" + step.batchIndex();
                // then
                assertThat(hash).isEqualTo(hashes.computeIfAbsent(key, ignored -> hash));
                assertThat(response.failure()).isNull();
                assertThat(response.cases()).hasSize(10).allSatisfy(c -> assertThat(c.actual()).isEqualTo(c.expected()));
                results.add(response);
            }
        }
        assertThat(hashes).hasSize(4);
        assertThat(results.stream().mapToLong(b -> b.usage().total()).sum()).isEqualTo(12 * 120);
        assertThat(results.stream().flatMap(b -> b.cases().stream())).hasSize(120);
    }

    @Test
    void 두조건의_wire본문은_System_추가블록_외에_동일하다() throws IOException {
        // given
        var batch = Issue55MicroBatchF0.batches(10).get(0);
        try (var fake = new FakeProvider()) {
            fake.enqueue(json(new BatchOutput(rows(batch))));
            fake.enqueue(json(new BatchOutput(rows(batch))));
            fake.enqueue(json(new BatchOutput(rows(batch))));
            // when: 기존 evaluate와 새 Control도 대조한다.
            Issue55MicroBatchEvaluation.evaluate(fake.client, batch);
            Issue55MicroBatchChecklist.evaluate(fake.client, batch, Arm.CONTROL);
            Issue55MicroBatchChecklist.evaluate(fake.client, batch, Arm.CANDIDATE);
            var original = JSON.readTree(fake.requests.remove());
            var control = JSON.readTree(fake.requests.remove());
            var candidate = JSON.readTree(fake.requests.remove());
            // then
            assertThat(control).isEqualTo(original);
            var object = (tools.jackson.databind.node.ObjectNode) candidate;
            var messages = (tools.jackson.databind.node.ArrayNode) object.path("messages");
            ((tools.jackson.databind.node.ObjectNode) messages.get(0)).put("content", SYSTEM_PROMPT);
            assertThat(candidate).isEqualTo(control);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"parse", "missing", "duplicate", "unknown", "validation", "truncated"})
    void 잘못된_응답은_UNRESOLVED로_남기며_정상품질로_계산하지_않는다(String mode) throws IOException {
        // given
        var batch = Issue55MicroBatchF0.batches(10).get(0);
        var rows = rows(batch);
        String finish = "stop";
        Failure expected = Failure.MAPPING;
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
            case "truncated" -> { finish = "length"; expected = Failure.TRUNCATED; }
            default -> throw new IllegalArgumentException();
        }
        try (var fake = new FakeProvider()) {
            fake.enqueue(mode.equals("parse") ? "{" : json(new BatchOutput(rows)), finish);
            // when
            var result = Issue55MicroBatchChecklist.evaluate(fake.client, batch, Arm.CANDIDATE);
            // then
            assertThat(result.failure()).isEqualTo(expected);
            assertThat(result.cases()).anySatisfy(c -> assertThat(c.actual()).isNull());
        }
    }

    @Test
    void 계약변경은_원문과_인증값_없는_오류로_실행전에_거절한다() {
        // given / when / then
        assertThatThrownBy(() -> Issue55MicroBatchChecklist.verifyWire(new Step(1, 0, Arm.CONTROL), "{}"))
                .isInstanceOf(IllegalStateException.class).hasMessage("CHECKLIST_REQUEST_CONTRACT_CHANGED");
    }

    private static ArrayList<OutputItem> rows(Batch batch) {
        return new ArrayList<>(batch.targets().stream().map(t ->
                new OutputItem(t.neutralId(), Issue55MicroBatchEvaluation.expected(t.testCase()))).toList());
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
