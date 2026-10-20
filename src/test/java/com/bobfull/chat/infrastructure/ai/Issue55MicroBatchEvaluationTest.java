package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.JSON;

import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.service.ModerationResultValidationSupport;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Batch;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchOutput;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Failure;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.OutputItem;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.Transition;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import tools.jackson.databind.JsonNode;

// 실 OpenAI key/환경설정을 읽지 않고 loopback HTTP 서버와 실제 Spring AI/SDK만 사용한다.
class Issue55MicroBatchEvaluationTest {
    private HttpServer server;
    private OpenAIClient sdk;
    private OpenAIClientAsync asyncSdk;
    private ChatClient client;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonNode> requests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void startFakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        sdk = OpenAiSetup.setupSyncClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "fake-issue55-key", null, null, null, null, false, false,
                Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(),
                ObservationRegistry.NOOP, null, List.of());
        asyncSdk = OpenAiSetup.setupAsyncClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "fake-issue55-key", null, null, null, null, false, false,
                Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(5), 3, null, Map.of(),
                ObservationRegistry.NOOP, null, List.of());
        client = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sdk)
                .openAiClientAsync(asyncSdk)
                .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).build()).build()).build();
    }

    @AfterEach
    void stopFakeProvider() {
        if (sdk != null) {
            sdk.close();
        }
        if (asyncSdk != null) {
            asyncSdk.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5, 10})
    void 고정_seed로_Rule_1건과_LLM_79건을_중복없이_분할한다(int size) {
        // given / when
        var plan = Issue55MicroBatchEvaluation.plan(size);
        var reference = Issue55MicroBatchEvaluation.plan(2);
        var targets = plan.batches().stream().flatMap(b -> b.targets().stream()).toList();

        // then
        assertThat(plan.ruleCases()).hasSize(1);
        assertThat(plan.ruleCases().get(0).caseId()).isEqualTo("HOLDOUT-PI-01");
        assertThat(plan.batches()).hasSize((79 + size - 1) / size);
        assertThat(targets).hasSize(79).doesNotHaveDuplicates();
        assertThat(targets).isEqualTo(reference.batches().stream().flatMap(b -> b.targets().stream()).toList());
        assertThat(plan).isEqualTo(Issue55MicroBatchEvaluation.plan(size));
        assertThat(targets).allSatisfy(t -> assertThat(t.neutralId()).matches("m\\d{3}"));
        assertThat(plan.batches().get(plan.batches().size() - 1).targets()).hasSize(79 % size);
        assertThat(plan.batches()).allSatisfy(b -> assertThat(b.outputCap()).isEqualTo(128 * b.targets().size()));
        System.out.printf("FAKE plan size=%d rule=1 llm=79 batches=%d last=%d seed=%d%n",
                size, plan.batches().size(), 79 % size, Issue55MicroBatchEvaluation.SEED);
    }

    @Test
    void 정상_응답은_정책와_Fewshot을_유지한_native_Schema_요청으로_매핑한다() {
        // given
        Batch batch = batch();
        enqueue(output(batch));

        // when
        BatchObservation result = Issue55MicroBatchEvaluation.evaluate(client, batch);

        // then
        assertThat(result.failure()).isNull();
        assertThat(result.cases()).hasSize(2).allSatisfy(c -> assertThat(c.resultMatches()).isTrue());
        assertThat(result.model()).isEqualTo("gpt-4o-mini-fake");
        assertThat(result.usage().total()).isEqualTo(120);
        assertThat(result.usage().cachedInput()).isZero();
        JsonNode request = requests.element();
        assertThat(request.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(request.path("messages").size()).isEqualTo(2);
        String system = request.path("messages").get(0).path("content").asText();
        assertThat(system).isEqualTo(ModerationPrompt.SYSTEM_PROMPT + Issue55MicroBatchEvaluation.BATCH_INSTRUCTIONS);
        assertThat(system.lines().filter(line -> line.contains("→")).count()).isEqualTo(15);
        JsonNode user = JSON.readTree(request.path("messages").get(1).path("content").asText());
        assertThat(user).isEqualTo(JSON.valueToTree(batch.input()));
        assertThat(user.toString()).doesNotContain("HOLDOUT-", "expectedResult", "caseType");
        JsonNode format = request.path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema").path("schema"))
                .isEqualTo(JSON.readTree(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void 역순_응답도_배열위치가_아니라_ID로_매핑한다() {
        // given
        Batch batch = batch();
        var rows = new ArrayList<>(output(batch).results());
        rows.set(0, new OutputItem(rows.get(0).id(), safe()));
        rows.set(1, new OutputItem(rows.get(1).id(), flagged()));
        Collections.reverse(rows);
        enqueue(new BatchOutput(rows));

        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch);

        // then
        assertThat(result.failure()).isNull();
        assertThat(result.cases().get(0).caseId()).isEqualTo(batch.targets().get(0).testCase().id());
        assertThat(result.cases().get(0).actual()).isEqualTo(safe());
        assertThat(result.cases().get(1).actual()).isEqualTo(flagged());
    }

    @Test
    void ID_누락은_누락집합과_개수불일치를_남기고_Batch를_실패시킨다() {
        // given
        Batch batch = batch();
        enqueue(new BatchOutput(output(batch).results().subList(0, 1)));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch);
        // then
        assertMappingFailure(result);
        assertThat(result.mapping().missing()).containsExactly(batch.targets().get(1).neutralId());
        assertThat(result.mapping().countMismatch()).isTrue();
    }

    @Test
    void 같은_개수여도_중복_ID를_덮어쓰지_않는다() {
        // given
        Batch batch = batch();
        var row = output(batch).results().get(0);
        enqueue(new BatchOutput(List.of(row, row)));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch);
        // then
        assertMappingFailure(result);
        assertThat(result.mapping().duplicate()).containsExactly(row.id());
        assertThat(result.mapping().countMismatch()).isFalse();
    }

    @Test
    void 같은_개수여도_요청에_없는_ID를_허용하지_않는다() {
        // given
        Batch batch = batch();
        enqueue(new BatchOutput(List.of(output(batch).results().get(0), new OutputItem("m999", safe()))));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch);
        // then
        assertMappingFailure(result);
        assertThat(result.mapping().unexpected()).containsExactly("m999");
        assertThat(result.mapping().countMismatch()).isFalse();
    }

    @Test
    void 요청보다_많은_결과를_잘라서_성공처리하지_않는다() {
        // given
        Batch batch = batch();
        var rows = new ArrayList<>(output(batch).results());
        rows.add(rows.get(0));
        enqueue(new BatchOutput(rows));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch);
        // then
        assertMappingFailure(result);
        assertThat(result.mapping().missing()).isEmpty();
        assertThat(result.mapping().countMismatch()).isTrue();
    }

    @Test
    void Parse_실패에도_Batch_Usage를_보존한다() {
        // given
        replies.add(success("not-json", "stop", true));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch());
        // then
        assertThat(result.failure()).isEqualTo(Failure.PARSE);
        assertThat(result.usage().total()).isEqualTo(120);
        assertThat(result.cases()).allSatisfy(c -> assertThat(c.actual()).isNull());
    }

    @Test
    void 일부_항목의_SAFE_조합위반은_기존_Validator로_거절한다() {
        // given
        Batch batch = batch();
        var rows = new ArrayList<>(output(batch).results());
        rows.set(0, new OutputItem(rows.get(0).id(),
                new ModerationResult(ModerationResultType.SAFE, Set.of(ModerationCategory.SPAM), RiskLevel.LOW)));
        enqueue(new BatchOutput(rows));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch);
        // then
        assertThat(result.failure()).isEqualTo(Failure.VALIDATION);
        assertThat(result.cases().get(0).actual()).isNull();
        assertThat(result.cases().get(0).transition()).isEqualTo(Transition.UNRESOLVED);
        assertThat(result.cases().get(0).newFn()).isFalse();
        assertThat(result.cases().get(1).actual()).isNotNull();
        assertThat(result.usage().total()).isEqualTo(120);
    }

    @Test
    void length로_끝난_응답은_JSON이_파싱되어도_출력잘림으로_거절한다() {
        // given
        replies.add(success(JSON.writeValueAsString(output(batch())), "length", true));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch());
        // then
        assertThat(result.failure()).isEqualTo(Failure.TRUNCATED);
        assertThat(result.usage().total()).isEqualTo(120);
        assertThat(result.cases()).allSatisfy(c -> assertThat(c.actual()).isNull());
    }

    @Test
    void Batch_전체_Provider_실패는_Usage를_0으로_꾸미지_않는다() {
        // given: 재시도 정책을 바꾸지 않고 비재시도 HTTP 400으로 Provider 예외를 재현한다.
        replies.add(new Reply(400, "{\"error\":{\"message\":\"fake rejection\",\"type\":\"invalid_request_error\"}}"));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch());
        // then
        assertThat(result.failure()).isEqualTo(Failure.PROVIDER);
        assertThat(result.usage()).isNull();
        assertThat(result.cases()).allSatisfy(c -> assertThat(c.failure()).isEqualTo(Failure.PROVIDER));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void Usage가_없는_정상응답은_측정불가로_보존한다() {
        // given
        replies.add(success(JSON.writeValueAsString(output(batch())), "stop", false));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch());
        // then
        assertThat(result.failure()).isNull();
        assertThat(result.usage()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"results\":[{\"id\":\"m001\",\"moderation\":{\"result\":\"UNKNOWN\",\"categories\":[],\"riskLevel\":\"LOW\"}}]}",
            "{\"results\":[{\"id\":\"m001\",\"moderation\":{\"result\":\"SAFE\",\"categories\":[]}}]}",
            "{\"results\":null}",
            "{\"results\":[],\"extra\":true}"
    })
    void Schema의_enum_필수필드_null_추가필드_위반을_거절한다(String content) {
        // given
        replies.add(success(content, "stop", true));
        // when
        var result = Issue55MicroBatchEvaluation.evaluate(client, batch());
        // then
        assertThat(result.failure()).isEqualTo(Failure.VALIDATION);
        assertThat(result.usage().total()).isEqualTo(120);
        assertThat(result.cases()).allSatisfy(c -> assertThat(c.actual()).isNull());
    }

    @Test
    void 실패한_Batch도_Usage는_한번_합산하고_미판정은_별도로_남긴다() {
        // given
        var plan = Issue55MicroBatchEvaluation.plan(10);
        List<BatchObservation> observations = new ArrayList<>();
        replies.add(success("not-json", "stop", true));
        replies.add(new Reply(400, "{\"error\":{\"message\":\"fake rejection\"}}"));
        plan.batches().subList(2, plan.batches().size()).forEach(b -> enqueue(output(b)));
        // when
        plan.batches().forEach(b -> observations.add(Issue55MicroBatchEvaluation.evaluate(client, b)));
        var summary = Issue55MicroBatchEvaluation.summarize(plan, observations);
        // then: Parse 실패 Usage 포함, Provider 실패의 미확인 Usage는 측정 건수에서 제외한다.
        assertThat(summary.logicalCalls()).isEqualTo(8);
        assertThat(summary.measuredUsageCalls()).isEqualTo(7);
        assertThat(summary.totalTokens()).isEqualTo(7 * 120);
        assertThat(summary.unresolved()).isEqualTo(20);
        assertThat(summary.resultMatches()).isEqualTo(60);
        assertThat(summary.confusion().total()).isEqualTo(60);
        assertThat(observations.subList(0, 2).stream().flatMap(b -> b.cases().stream())).allSatisfy(c -> {
            assertThat(c.transition()).isEqualTo(Transition.UNRESOLVED);
            assertThat(c.newFn()).isFalse();
            assertThat(c.newFp()).isFalse();
            assertThat(c.categoryRegression()).isFalse();
            assertThat(c.actionabilityRegression()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5, 10})
    void 전체_Fake_평가는_80건을_복원하고_Usage를_Batch당_한번만_합한다(int size) {
        // given: 정답을 돌려주는 Fake이며 모델 품질 측정이 아니다.
        var plan = Issue55MicroBatchEvaluation.plan(size);
        List<BatchObservation> observations = new ArrayList<>();
        // when
        for (Batch batch : plan.batches()) {
            enqueue(output(batch));
            observations.add(Issue55MicroBatchEvaluation.evaluate(client, batch));
        }
        var summary = Issue55MicroBatchEvaluation.summarize(plan, observations);
        // then
        assertThat(observations).allSatisfy(b -> assertThat(b.failure()).isNull());
        assertThat(summary.totalCases()).isEqualTo(80);
        assertThat(summary.unresolved()).isZero();
        assertThat(summary.resultMatches()).isEqualTo(80);
        assertThat(summary.categoryMatches()).isEqualTo(80);
        assertThat(summary.actionabilityMatches()).isEqualTo(80);
        assertThat(summary.confusion()).isEqualTo(new ModerationEvaluationMetrics.ConfusionCounts(42, 0, 0, 38));
        assertThat(summary.logicalCalls()).isEqualTo(plan.batches().size());
        assertThat(attempts.get()).isEqualTo(plan.batches().size());
        assertThat(summary.measuredUsageCalls()).isEqualTo(plan.batches().size());
        assertThat(summary.promptTokens()).isEqualTo(100L * plan.batches().size());
        assertThat(summary.completionTokens()).isEqualTo(20L * plan.batches().size());
        assertThat(summary.totalTokens()).isEqualTo(120L * plan.batches().size());
        assertThat(observations.stream().flatMap(b -> b.cases().stream())
                .filter(c -> c.transition() == Transition.IMPROVED)).hasSize(8);
        assertThat(observations.stream().flatMap(b -> b.cases().stream())
                .filter(c -> c.transition() == Transition.MAINTAINED)).hasSize(71);
        List<BatchObservation> duplicate = new ArrayList<>(observations);
        duplicate.add(observations.get(0));
        assertThatThrownBy(() -> Issue55MicroBatchEvaluation.summarize(plan, duplicate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Issue55MicroBatchEvaluation.summarize(plan, observations.subList(1, observations.size())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 새_FN_FP와_Category_Actionability_회귀를_Case별로_구분한다() {
        // given
        var cases = SpringAiModerationHeldoutEvaluationTest.heldoutCasesPolicyV2();
        var positive = cases.stream().filter(c -> c.expectedResult() == ModerationResultType.FLAGGED).findFirst().orElseThrow();
        var negative = cases.stream().filter(c -> c.expectedResult() == ModerationResultType.SAFE
                && !Issue55MicroBatchEvaluation.BEFORE_FALSE_POSITIVES.containsKey(c.id())).findFirst().orElseThrow();
        // when
        var fn = new Issue55MicroBatchEvaluation.CaseObservation(positive.id(), "b001",
                Issue55MicroBatchEvaluation.expected(positive), safe(), null);
        var fp = new Issue55MicroBatchEvaluation.CaseObservation(negative.id(), "b001",
                Issue55MicroBatchEvaluation.expected(negative), flagged(), null);
        // then
        assertThat(fn.newFn()).isTrue();
        assertThat(fp.newFp()).isTrue();
        assertThat(List.of(fn, fp)).allSatisfy(c -> {
            assertThat(c.transition()).isEqualTo(Transition.REGRESSION);
            assertThat(c.categoryRegression()).isTrue();
            assertThat(c.actionabilityRegression()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 5, 9, 10})
    void 긴_유효출력의_tokenizer_추정은_실제항목수_기반_cap보다_작다(int count) {
        // given: 모든 category + HIGH, 긴 pretty JSON. Provider Usage가 아닌 로컬 추정이다.
        var longResult = new ModerationResult(ModerationResultType.FLAGGED,
                Set.of(ModerationCategory.PROFANITY, ModerationCategory.PERSONAL_INFORMATION, ModerationCategory.SPAM),
                RiskLevel.HIGH);
        ModerationResultValidationSupport.validate(longResult);
        List<OutputItem> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new OutputItem("m%03d".formatted(i + 1), longResult));
        }
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(new BatchOutput(rows));
        // when
        int estimated = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE).countTokens(json);
        // then
        assertThat(estimated).isLessThan(128 * count);
        assertThat(JSON.readValue(json, BatchOutput.class).results()).hasSize(count);
        System.out.printf("OUTPUT_CAP_PREFLIGHT n=%d estimatedTokens=%d cap=%d (not Provider usage)%n",
                count, estimated, 128 * count);
    }

    private static Batch batch() {
        return Issue55MicroBatchEvaluation.plan(2).batches().get(0);
    }

    private static ModerationResult safe() {
        return new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
    }

    private static ModerationResult flagged() {
        return new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.SPAM), RiskLevel.MEDIUM);
    }

    private static BatchOutput output(Batch batch) {
        return new BatchOutput(batch.targets().stream().map(t ->
                new OutputItem(t.neutralId(), Issue55MicroBatchEvaluation.expected(t.testCase()))).toList());
    }

    private void enqueue(BatchOutput output) {
        replies.add(success(JSON.writeValueAsString(output), "stop", true));
    }

    private static void assertMappingFailure(BatchObservation result) {
        assertThat(result.failure()).isEqualTo(Failure.MAPPING);
        assertThat(result.cases()).allSatisfy(c -> assertThat(c.actual()).isNull());
        assertThat(result.usage().total()).isEqualTo(120);
    }

    private static Reply success(String content, String finish, boolean includeUsage) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", "chatcmpl-fake-issue55");
        body.put("object", "chat.completion");
        body.put("created", 1720000000);
        body.put("model", "gpt-4o-mini-fake");
        body.put("choices", List.of(Map.of("index", 0, "finish_reason", finish,
                "message", Map.of("role", "assistant", "content", content))));
        if (includeUsage) {
            body.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120,
                    "prompt_tokens_details", Map.of("cached_tokens", 0)));
        }
        return new Reply(200, JSON.writeValueAsString(body));
    }

    private void handle(HttpExchange exchange) throws IOException {
        attempts.incrementAndGet();
        requests.add(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = replies.poll();
        if (reply == null) {
            reply = new Reply(400, "{\"error\":{\"message\":\"no fake response\"}}");
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Reply(int status, String body) { }
}
