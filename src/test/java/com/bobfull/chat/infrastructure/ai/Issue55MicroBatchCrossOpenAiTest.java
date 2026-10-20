package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosis.Cell;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosis.Composition;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchCrossDiagnosis.Call;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchUsage;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okio.Buffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import tools.jackson.databind.JsonNode;

@Tag("openai-evaluation")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class Issue55MicroBatchCrossOpenAiTest {
    private static final String EXPECTED_MODEL = "gpt-4o-mini-2024-07-18";
    private final List<Call> calls = new ArrayList<>();
    private final List<HttpAttempt> attempts = new ArrayList<>();
    private final Map<Cell, String> wireHashes = new HashMap<>();
    private final AtomicInteger activeLogicalCall = new AtomicInteger();
    private final String startedAt = Instant.now().toString();
    private Cell activeCell;
    private Composition composition;
    private Path report;
    private double priorUsd;
    private double chargedUpperUsd;
    private String status = "PREFLIGHT";
    private String stopReason;

    @Test
    void 자식프로세스의_Key_존재만_확인하며_Provider를_호출하지_않는다() {
        // given / when
        boolean present = System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank();
        // then: 값을 assertion이나 로그에 전달하지 않는다.
        assertThat(present).isTrue();
        System.out.println("CROSS_CHILD_KEY_PRESENT=true; providerCalls=0");
    }

    @Test
    void C0_고정구성_9조건을_3회씩_27호출한다() throws IOException {
        // given / when / then
        run(Composition.C0);
    }

    @Test
    void C0_통과후에만_C1_교체구성_27호출한다() throws IOException {
        // given / when / then
        run(Composition.C1);
    }

    private void run(Composition selected) throws IOException {
        composition = selected;
        report = report(selected);
        assertThat(Files.exists(report)).as("Existing report: never automatically repeat paid calls").isFalse();
        var parent = Issue55MicroBatchEvaluation.JSON.readTree(Files.readString(selected == Composition.C0
                ? Path.of("docs/110-records/evidence/v3/55-ai-moderation-efficiency/microbatch-size-b.json")
                : report(Composition.C0)));
        assertThat(parent.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(parent.path("hashes")).isEqualTo(Issue55MicroBatchEvaluation.JSON.valueToTree(hashes()));
        priorUsd = parent.path("chargedCumulativeUpperUsd").asDouble();
        if (selected == Composition.C0) {
            assertThat(priorUsd).isCloseTo(Issue55MicroBatchCrossDiagnosis.PRIOR_ESTIMATED_USD,
                    org.assertj.core.data.Offset.offset(1e-9));
        } else {
            assertThat(parent.path("composition").asText()).isEqualTo("C0");
            assertThat(parent.path("calls").size()).isEqualTo(27);
            assertThat(parent.path("sourceHashes")).isEqualTo(Issue55MicroBatchEvaluation.JSON.valueToTree(sourceHashes()));
            for (var call : parent.path("calls")) {
                assertThat(call.path("response").path("failure").isNull()).isTrue();
                assertThat(call.path("response").path("cases").size()).isEqualTo(5);
                assertThat(call.path("response").path("model").asText()).isEqualTo(EXPECTED_MODEL);
                assertThat(call.path("wireBodySha256").asText()).hasSize(64);
            }
        }
        chargedUpperUsd = priorUsd;
        double reserve = Issue55MicroBatchCrossDiagnosis.forecast(selected).allFourAttemptsEstimateUsd();
        if (selected == Composition.C0) reserve += Issue55MicroBatchCrossDiagnosis.forecast(Composition.C1).allFourAttemptsEstimateUsd();
        assertThat(chargedUpperUsd + reserve).isLessThan(.50);
        String key = System.getenv("OPENAI_API_KEY");
        assertThat(key != null && !key.isBlank()).isTrue();
        System.out.printf(Locale.ROOT, "CROSS_PREFLIGHT composition=%s calls=27 priorUsd=%.8f fourAttemptsPlanUsd=%.8f limitUsd=0.50%n",
                composition, chargedUpperUsd, reserve);
        save();

        OpenAiHttpClientBuilderCustomizer customizer = http -> http.interceptor(chain -> {
            // 인증 header는 읽거나 저장하지 않는다. 본문은 메모리 검증 후 해시만 보존한다.
            Buffer body = new Buffer();
            chain.request().body().writeTo(body);
            String wireHash = Issue55MicroBatchCrossDiagnosis.verifyWireBody(activeCell, body.readUtf8());
            String previous = wireHashes.putIfAbsent(activeCell, wireHash);
            if (previous != null && !previous.equals(wireHash)) {
                stopReason = "WIRE_BODY_CHANGED";
                throw new IllegalStateException("CROSS_WIRE_BODY_CHANGED");
            }
            double reservation = Issue55MicroBatchComparison.cost(128_000, 640, 0);
            if (chargedUpperUsd + reservation > .50) {
                stopReason = "BUDGET_LIMIT";
                throw new IllegalStateException("ISSUE55_BUDGET_LIMIT");
            }
            chargedUpperUsd += reservation;
            long started = System.nanoTime();
            try {
                var response = chain.proceed(chain.request());
                BatchUsage usage = null;
                String errorCode = null;
                var responseIds = new ArrayList<String>();
                try {
                    JsonNode tree = Issue55MicroBatchEvaluation.JSON.readTree(response.peekBody(1_000_000).string());
                    var u = tree.path("usage");
                    if (u.path("prompt_tokens").isIntegralNumber() && u.path("completion_tokens").isIntegralNumber()
                            && u.path("total_tokens").isIntegralNumber()) {
                        Long cached = u.path("prompt_tokens_details").path("cached_tokens").isIntegralNumber()
                                ? u.path("prompt_tokens_details").path("cached_tokens").asLong() : null;
                        usage = new BatchUsage(u.path("prompt_tokens").asLong(), u.path("completion_tokens").asLong(),
                                u.path("total_tokens").asLong(), cached);
                    }
                    String code = tree.path("error").path("code").asText("");
                    if (code.matches("[a-zA-Z0-9_]{1,80}")) errorCode = code;
                    String content = tree.path("choices").path(0).path("message").path("content").asText("");
                    if (!content.isBlank()) {
                        for (var row : Issue55MicroBatchEvaluation.JSON.readTree(content).path("results")) {
                            String id = row.path("id").asText("");
                            if (id.matches("m[0-9]{3}")) responseIds.add(id);
                        }
                    }
                } catch (RuntimeException ignored) {
                    errorCode = "unclassified_response";
                }
                double charged = validUsage(usage) ? Issue55MicroBatchComparison.cost(usage.prompt(), usage.completion(), 0) : reservation;
                chargedUpperUsd += charged - reservation;
                attempts.add(new HttpAttempt(attempts.size() + 1, activeLogicalCall.get(), response.code(), errorCode,
                        wireHash, usage, charged, System.nanoTime() - started, List.copyOf(responseIds)));
                return response;
            } catch (IOException network) {
                attempts.add(new HttpAttempt(attempts.size() + 1, activeLogicalCall.get(), null, "network_error",
                        wireHash, null, reservation, System.nanoTime() - started, List.of()));
                throw network;
            }
        });
        OpenAIClient sync = OpenAiSetup.setupSyncClient("https://api.openai.com/v1", key, null, null, null, null,
                false, false, Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(90), 3, null, Map.of(),
                ObservationRegistry.NOOP, null, List.of(customizer));
        OpenAIClientAsync async = OpenAiSetup.setupAsyncClient("https://api.openai.com/v1", key, null, null, null, null,
                false, false, Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(90), 3, null, Map.of(),
                ObservationRegistry.NOOP, null, List.of(customizer));
        try {
            var client = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sync).openAiClientAsync(async)
                    .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).build()).build()).build();
            status = "RUNNING";
            for (var step : Issue55MicroBatchCrossDiagnosis.schedule(selected)) {
                activeCell = step.cell();
                var batch = Issue55MicroBatchCrossDiagnosis.batch(activeCell);
                long started = System.nanoTime();
                String callStarted = Instant.now().toString();
                int attemptStart = attempts.size();
                activeLogicalCall.incrementAndGet();
                var result = Issue55MicroBatchEvaluation.evaluate(client, batch);
                var focal = batch.targets().get(Issue55MicroBatchCrossDiagnosis.position(batch.targets(), activeCell.focusId()));
                int outputPosition = attempts.size() == attemptStart ? 0
                        : attempts.get(attempts.size() - 1).responseIds().indexOf(focal.neutralId()) + 1;
                calls.add(new Call(step, Issue55MicroBatchSizeComparison.requestHash(batch), wireHashes.get(activeCell),
                        callStarted, System.nanoTime() - started, attempts.size() - attemptStart, outputPosition, result));
                if (result.failure() != null || !validUsage(result.usage()) || !EXPECTED_MODEL.equals(result.model())) {
                    stopReason = result.failure() != null ? "RESPONSE_CONTRACT_" + result.failure()
                            : !validUsage(result.usage()) ? "INVALID_USAGE" : "MODEL_CHANGED";
                    throw new AssertionError("Cross diagnosis stopped: " + stopReason);
                }
                save();
                if (calls.size() % 9 == 0) {
                    System.out.printf(Locale.ROOT, "CROSS_PROGRESS composition=%s logical=%d/27 http=%d cumulativeUpperUsd=%.8f%n",
                            composition, calls.size(), attempts.size(), chargedUpperUsd);
                }
            }
            assertThat(calls).hasSize(27);
            assertThat(wireHashes).hasSize(9);
            assertThat(chargedUpperUsd).isLessThan(.50);
            status = "COMPLETED";
            System.out.printf(Locale.ROOT, "CROSS_GATE composition=%s contract=PASS usage=PASS wireIdentical=PASS cumulativeUpperUsd=%.8f%n",
                    composition, chargedUpperUsd);
        } finally {
            if (!status.equals("COMPLETED")) {
                status = "STOPPED";
                if (stopReason == null) stopReason = "EXECUTION_ABORTED";
            }
            save();
            sync.close();
            async.close();
        }
    }

    static boolean validUsage(BatchUsage usage) {
        return usage != null && usage.prompt() >= 0 && usage.completion() >= 0
                && usage.total() == usage.prompt() + usage.completion()
                && (usage.cachedInput() == null || (usage.cachedInput() >= 0 && usage.cachedInput() <= usage.prompt()));
    }

    private static Path report(Composition composition) {
        return Path.of("build/issue55/microbatch-f1f2-" + composition.name().toLowerCase(Locale.ROOT) + ".json");
    }

    private static Map<String, String> hashes() {
        return Map.of("singleSystemPrompt", Issue55MicroBatchF0.sha256(ModerationPrompt.SYSTEM_PROMPT),
                "batchSystemPrompt", Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.SYSTEM_PROMPT),
                "singleSchema", Issue55MicroBatchF0.sha256(Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()),
                "batchSchema", Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()));
    }

    private static Map<String, String> sourceHashes() throws IOException {
        Map<String, String> hashes = new HashMap<>();
        for (String name : List.of("Issue55MicroBatchCrossDiagnosis.java", "Issue55MicroBatchCrossDiagnosisTest.java", "Issue55MicroBatchCrossOpenAiTest.java")) {
            hashes.put(name, Issue55MicroBatchF0.sha256(Files.readString(Path.of("src/test/java/com/bobfull/chat/infrastructure/ai", name))));
        }
        return Map.copyOf(hashes);
    }

    private void save() throws IOException {
        Files.createDirectories(report.getParent());
        var snapshot = new Snapshot(startedAt, Instant.now().toString(), status, stopReason, composition,
                "339423fbca943d578bf07793ff336490904290eb + uncommitted test source", Issue55MicroBatchEvaluation.MODEL,
                ModerationPrompt.PROMPT_VERSION, ModerationPrompt.POLICY_VERSION, 55L,
                "Batch5; C0 then C1; sampling/provider seed unspecified; SDK maxRetries=3; timeout=90s; cap=640",
                hashes(), sourceHashes(), Issue55MicroBatchF0.manifest(), Issue55MicroBatchCrossDiagnosis.requests(composition),
                Issue55MicroBatchCrossDiagnosis.schedule(composition), Issue55MicroBatchCrossDiagnosis.forecast(composition),
                priorUsd, chargedUpperUsd, List.copyOf(attempts), List.copyOf(calls));
        Files.writeString(report, Issue55MicroBatchEvaluation.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    }

    record HttpAttempt(int number, int logicalCall, Integer status, String errorCode, String wireBodySha256,
                       BatchUsage usage, double chargedUpperUsd, long nanos, List<String> responseIds) { }
    record Snapshot(String startedAt, String recordedAt, String status, String stopReason, Composition composition,
                    String head, String configuredModel, String promptVersion, String policyVersion, long datasetSeed, String options,
                    Map<String, String> hashes, Map<String, String> sourceHashes, List<Issue55MicroBatchF0.Manifest> manifest,
                    List<Issue55MicroBatchCrossDiagnosis.Request> requests, List<Issue55MicroBatchCrossDiagnosis.Step> schedule,
                    Issue55MicroBatchComparison.Forecast forecast, double priorEstimatedUsd, double chargedCumulativeUpperUsd,
                    List<HttpAttempt> httpAttempts, List<Call> calls) { }
}
