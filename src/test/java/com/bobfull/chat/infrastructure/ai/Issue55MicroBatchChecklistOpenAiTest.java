package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchChecklist.*;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchUsage;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
class Issue55MicroBatchChecklistOpenAiTest {
    private static final Path REPORT = Path.of("build/issue55/microbatch-b10-checklist-diagnostic.json");
    private static final String EXPECTED_MODEL = "gpt-4o-mini-2024-07-18";
    private final List<Call> calls = new ArrayList<>();
    private final List<HttpAttempt> attempts = new ArrayList<>();
    private final Map<String, String> wireHashes = new HashMap<>();
    private final AtomicInteger activeLogicalCall = new AtomicInteger();
    private final String startedAt = Instant.now().toString();
    private Step activeStep;
    private double priorUsd;
    private double chargedUpperUsd;
    private String status = "PREFLIGHT";
    private String stopReason;

    @Test
    void 자식프로세스_Key_존재만_확인하고_Provider는_호출하지_않는다() {
        // given / when / then: key 자체는 assertion/log에 전달하지 않는다.
        assertThat(System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank()).isTrue();
        System.out.println("CHECKLIST_CHILD_KEY_PRESENT=true; providerCalls=0");
    }

    @Test
    void 진단20건_Control_Candidate를_3회씩_12호출만_비교한다() throws IOException {
        // given
        assertThat(Files.exists(REPORT)).as("Never replay paid calls when a checkpoint exists").isFalse();
        var parent = Issue55MicroBatchEvaluation.JSON.readTree(Files.readString(Path.of(
                "docs/110-records/evidence/v3/55-ai-moderation-efficiency/microbatch-f1f2-c1.json")));
        assertThat(parent.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(parent.path("hashes")).isEqualTo(Issue55MicroBatchEvaluation.JSON.valueToTree(baseHashes()));
        assertThat(parent.path("manifest")).isEqualTo(Issue55MicroBatchEvaluation.JSON.valueToTree(Issue55MicroBatchF0.manifest()));
        priorUsd = parent.path("chargedCumulativeUpperUsd").asDouble();
        assertThat(priorUsd).isCloseTo(.12402005, org.assertj.core.data.Offset.offset(1e-9));
        chargedUpperUsd = priorUsd;
        double forecast = Issue55MicroBatchChecklist.forecast().stream()
                .mapToDouble(Issue55MicroBatchComparison.Forecast::allFourAttemptsEstimateUsd).sum();
        assertThat(priorUsd + forecast).isLessThan(.50);
        String key = System.getenv("OPENAI_API_KEY");
        assertThat(key != null && !key.isBlank()).isTrue();
        System.out.printf(Locale.ROOT, "CHECKLIST_PREFLIGHT calls=12 priorUsd=%.8f fourAttemptsPlanUsd=%.8f limit=0.50%n",
                priorUsd, forecast);
        save();
        OpenAiHttpClientBuilderCustomizer customizer = http -> http.interceptor(chain -> {
            // 인증 header는 읽거나 저장하지 않는다. 본문은 메모리 검증 후 해시만 보존한다.
            Buffer body = new Buffer();
            chain.request().body().writeTo(body);
            String wireHash = Issue55MicroBatchChecklist.verifyWire(activeStep, body.readUtf8());
            String previous = wireHashes.putIfAbsent(activeStep.arm() + ":" + activeStep.batchIndex(), wireHash);
            if (previous != null && !previous.equals(wireHash)) {
                stopReason = "WIRE_BODY_CHANGED";
                throw new IllegalStateException("CHECKLIST_WIRE_BODY_CHANGED");
            }
            double reservation = Issue55MicroBatchComparison.cost(128_000, 1280, 0);
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
            // when: 새40건은 이 실행 경로에 연결하지 않는다. 앞의 진단20건만 반복한다.
            for (var step : Issue55MicroBatchChecklist.schedule()) {
                activeStep = step;
                var batch = Issue55MicroBatchF0.batches(10).get(step.batchIndex());
                int attemptStart = attempts.size();
                activeLogicalCall.incrementAndGet();
                long started = System.nanoTime();
                String callStarted = Instant.now().toString();
                var result = Issue55MicroBatchChecklist.evaluate(client, batch, step.arm());
                calls.add(new Call(step, wireHashes.get(step.arm() + ":" + step.batchIndex()), callStarted,
                        System.nanoTime() - started, attempts.size() - attemptStart, result));
                if (result.failure() != null || !validUsage(result.usage()) || !EXPECTED_MODEL.equals(result.model())) {
                    stopReason = result.failure() != null ? "RESPONSE_CONTRACT_" + result.failure()
                            : !validUsage(result.usage()) ? "INVALID_USAGE" : "MODEL_CHANGED";
                    throw new AssertionError("Checklist stopped: " + stopReason);
                }
                // 품질 회귀는 숨기지 않고 기록한다. 계약/예산 실패와 품질 실패를 구분한다.
                save();
                System.out.printf(Locale.ROOT, "CHECKLIST_PROGRESS logical=%d/12 arm=%s repeat=%d batch=%d http=%d cumulativeUpperUsd=%.8f%n",
                        calls.size(), step.arm(), step.repeat(), step.batchIndex() + 1, attempts.size(), chargedUpperUsd);
            }
            // then
            assertThat(calls).hasSize(12);
            assertThat(wireHashes).hasSize(4);
            assertThat(chargedUpperUsd).isLessThan(.50);
            status = "COMPLETED";
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

    static boolean validUsage(BatchUsage u) {
        return u != null && u.prompt() >= 0 && u.completion() >= 0 && u.total() == u.prompt() + u.completion()
                && (u.cachedInput() == null || (u.cachedInput() >= 0 && u.cachedInput() <= u.prompt()));
    }

    private static Map<String, String> baseHashes() {
        return Map.of("singleSystemPrompt", Issue55MicroBatchF0.sha256(ModerationPrompt.SYSTEM_PROMPT),
                "batchSystemPrompt", Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.SYSTEM_PROMPT),
                "singleSchema", Issue55MicroBatchF0.sha256(Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()),
                "batchSchema", Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()));
    }

    private void save() throws IOException {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("startedAt", startedAt);
        snapshot.put("recordedAt", Instant.now().toString());
        snapshot.put("status", status);
        snapshot.put("stopReason", stopReason);
        snapshot.put("head", "339423fbca943d578bf07793ff336490904290eb + uncommitted test source");
        snapshot.put("model", Issue55MicroBatchEvaluation.MODEL);
        snapshot.put("promptVersion", ModerationPrompt.PROMPT_VERSION);
        snapshot.put("policyVersion", ModerationPrompt.POLICY_VERSION);
        snapshot.put("options", "Batch10; cap1280; sampling/provider seed unspecified; SDK retries3; timeout90s");
        snapshot.put("baseHashes", baseHashes());
        snapshot.put("controlSystem", Issue55MicroBatchChecklist.system(Arm.CONTROL));
        snapshot.put("candidateAddition", Issue55MicroBatchChecklist.CHECKLIST);
        snapshot.put("candidateSystemSha256", Issue55MicroBatchF0.sha256(Issue55MicroBatchChecklist.system(Arm.CANDIDATE)));
        var sources = new TreeMap<String, String>();
        for (String name : List.of("Issue55MicroBatchChecklist.java", "Issue55MicroBatchChecklistTest.java", "Issue55MicroBatchChecklistOpenAiTest.java")) {
            sources.put(name, Issue55MicroBatchF0.sha256(Files.readString(Path.of("src/test/java/com/bobfull/chat/infrastructure/ai", name))));
        }
        snapshot.put("sourceHashes", sources);
        snapshot.put("manifest", Issue55MicroBatchF0.manifest());
        snapshot.put("schedule", Issue55MicroBatchChecklist.schedule());
        snapshot.put("forecast", Issue55MicroBatchChecklist.forecast());
        snapshot.put("priorEstimatedUsd", priorUsd);
        snapshot.put("chargedCumulativeUpperUsd", chargedUpperUsd);
        snapshot.put("httpAttempts", List.copyOf(attempts));
        snapshot.put("calls", List.copyOf(calls));
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, Issue55MicroBatchEvaluation.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    }

    record HttpAttempt(int number, int logicalCall, Integer status, String errorCode, String wireBodySha256,
                       BatchUsage usage, double chargedUpperUsd, long nanos, List<String> responseIds) { }
}
