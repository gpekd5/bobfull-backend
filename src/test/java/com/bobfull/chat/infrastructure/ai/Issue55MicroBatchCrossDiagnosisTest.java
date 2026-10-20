package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosis.Cell;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosis.Composition;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosis.Order;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchOutput;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.OutputItem;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;

class Issue55MicroBatchCrossDiagnosisTest {
    @Test
    void C0는_기존입력이고_C1은_대상과_위치를_유지하며_한건만_교체한다() {
        // given / when / then
        for (String focus : Issue55MicroBatchCrossDiagnosis.FOCUS) {
            for (Order order : Order.values()) {
                var c0 = Issue55MicroBatchCrossDiagnosis.batch(new Cell(Composition.C0, focus, order));
                var c1 = Issue55MicroBatchCrossDiagnosis.batch(new Cell(Composition.C1, focus, order));
                assertThat(c0.targets()).hasSize(5).doesNotHaveDuplicates();
                assertThat(c1.targets()).hasSize(5).doesNotHaveDuplicates();
                assertThat(c0.outputCap()).isEqualTo(640);
                assertThat(c1.outputCap()).isEqualTo(640);
                int focal = Issue55MicroBatchCrossDiagnosis.position(c0.targets(), focus);
                assertThat(Issue55MicroBatchCrossDiagnosis.position(c1.targets(), focus)).isEqualTo(focal);
                assertThat(c1.targets().get(focal)).isEqualTo(c0.targets().get(focal));
                int changes = 0;
                for (int i = 0; i < 5; i++) {
                    if (!c0.targets().get(i).equals(c1.targets().get(i))) changes++;
                }
                assertThat(changes).isEqualTo(1);
                assertThat(c1.targets()).allSatisfy(t -> assertThat(Issue55MicroBatchF0.targets()).contains(t));
                if (order == Order.O0) {
                    assertThat(Issue55MicroBatchF0.batches(5).stream().map(b -> b.input()).toList()).contains(c0.input());
                }
            }
        }
    }

    @Test
    void 순서변경은_구성_ID_원문_GT를_보존하고_대상고정과_대상이동을_구분한다() {
        // given / when / then
        for (Composition composition : Composition.values()) {
            for (String focus : Issue55MicroBatchCrossDiagnosis.FOCUS) {
                var b0 = Issue55MicroBatchCrossDiagnosis.batch(new Cell(composition, focus, Order.O0));
                var b1 = Issue55MicroBatchCrossDiagnosis.batch(new Cell(composition, focus, Order.O1));
                var b2 = Issue55MicroBatchCrossDiagnosis.batch(new Cell(composition, focus, Order.O2));
                int focal = Issue55MicroBatchCrossDiagnosis.position(b0.targets(), focus);
                assertThat(b1.targets()).containsExactlyInAnyOrderElementsOf(b0.targets());
                assertThat(b2.targets()).containsExactlyInAnyOrderElementsOf(b0.targets());
                assertThat(b1.targets().get(focal)).isEqualTo(b0.targets().get(focal));
                var companions = new ArrayList<>(b0.targets());
                companions.remove(focal);
                Collections.reverse(companions);
                assertThat(b1.targets().stream().filter(t -> !t.testCase().id().equals(focus)).toList()).isEqualTo(companions);
                assertThat(Issue55MicroBatchCrossDiagnosis.position(b2.targets(), focus)).isEqualTo(focal == 0 ? 4 : 0);
                assertThat(b2.targets().stream().filter(t -> !t.testCase().id().equals(focus)).toList())
                        .isEqualTo(b0.targets().stream().filter(t -> !t.testCase().id().equals(focus)).toList());
            }
        }
    }

    @Test
    void 각구성은_9개조건을_3회씩_27번_실행하며_순서와_명세는_재현가능하다() {
        // given / when / then
        for (Composition composition : Composition.values()) {
            var steps = Issue55MicroBatchCrossDiagnosis.schedule(composition);
            assertThat(steps).hasSize(27).doesNotHaveDuplicates();
            assertThat(steps).isEqualTo(Issue55MicroBatchCrossDiagnosis.schedule(composition));
            assertThat(Issue55MicroBatchCrossDiagnosis.requests(composition)).hasSize(9);
            for (var cell : steps.stream().map(s -> s.cell()).distinct().toList()) {
                assertThat(steps.stream().filter(s -> s.cell().equals(cell)).map(s -> s.repeat()).toList()).containsExactly(1, 2, 3);
            }
        }
        var c0 = Issue55MicroBatchCrossDiagnosis.schedule(Composition.C0);
        var c1 = Issue55MicroBatchCrossDiagnosis.schedule(Composition.C1);
        for (int i = 0; i < 27; i++) {
            assertThat(c1.get(i).repeat()).isEqualTo(c0.get(i).repeat());
            assertThat(c1.get(i).cell().focusId()).isEqualTo(c0.get(i).cell().focusId());
            assertThat(c1.get(i).cell().order()).isEqualTo(c0.get(i).cell().order());
        }
    }

    @Test
    void 무과금_견적은_54호출과_현재누적비용_및_전체OutputCap을_반영한다() {
        // given / when
        double fourAttempts = 0;
        for (Composition composition : Composition.values()) {
            var forecast = Issue55MicroBatchCrossDiagnosis.forecast(composition);
            // then
            assertThat(forecast.logicalCalls()).isEqualTo(27);
            assertThat(forecast.outputCapSum()).isEqualTo(17_280);
            fourAttempts += forecast.allFourAttemptsEstimateUsd();
            System.out.println("CROSS_LOCAL_TOKENIZER_FORECAST " + composition + " " + forecast);
        }
        assertThat(Issue55MicroBatchCrossDiagnosis.PRIOR_ESTIMATED_USD + fourAttempts).isLessThan(.50);
    }

    @Test
    void Fake54요청은_역순응답을_ID로_매핑하고_같은조건의_wire본문이_동일하다() throws IOException {
        // given
        var hashes = new HashMap<Cell, String>();
        var usages = new ArrayList<Issue55MicroBatchEvaluation.BatchObservation>();
        try (var fake = new FakeProvider()) {
            for (Composition composition : Composition.values()) {
                for (var step : Issue55MicroBatchCrossDiagnosis.schedule(composition)) {
                    var batch = Issue55MicroBatchCrossDiagnosis.batch(step.cell());
                    var rows = new ArrayList<>(batch.targets().stream().map(t ->
                            new OutputItem(t.neutralId(), Issue55MicroBatchEvaluation.expected(t.testCase()))).toList());
                    Collections.reverse(rows);
                    fake.enqueue(json(new BatchOutput(rows)));
                    // when
                    var result = Issue55MicroBatchEvaluation.evaluate(fake.client, batch);
                    String hash = Issue55MicroBatchCrossDiagnosis.verifyWireBody(step.cell(), fake.requests.remove());
                    // then
                    assertThat(hash).isEqualTo(hashes.computeIfAbsent(step.cell(), ignored -> hash));
                    assertThat(result.failure()).isNull();
                    assertThat(result.cases()).hasSize(5).allSatisfy(c -> assertThat(c.actual()).isEqualTo(c.expected()));
                    usages.add(result);
                }
            }
        }
        assertThat(hashes).hasSize(18);
        assertThat(usages).hasSize(54);
        // Batch Usage만 합산한다. Case 다섯 건에 Token을 복제하지 않는다.
        assertThat(usages.stream().mapToLong(c -> c.usage().total()).sum()).isEqualTo(54 * 120);
    }

    @Test
    void 변경된_wire입력은_본문이나_인증정보를_오류에_노출하지_않고_거절한다() {
        // given / when / then
        var cell = new Cell(Composition.C0, Issue55MicroBatchCrossDiagnosis.FOCUS.get(0), Order.O0);
        assertThatThrownBy(() -> Issue55MicroBatchCrossDiagnosis.verifyWireBody(cell, "{}"))
                .isInstanceOf(IllegalStateException.class).hasMessage("CROSS_REQUEST_CONTRACT_CHANGED");
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
            replies.add(json(Map.of("id", "chatcmpl-fake-cross", "object", "chat.completion", "created", 1720000000,
                    "model", "gpt-4o-mini-fake", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
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
