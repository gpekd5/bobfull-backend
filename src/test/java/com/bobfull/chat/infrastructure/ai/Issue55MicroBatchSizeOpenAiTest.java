package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
class Issue55MicroBatchSizeOpenAiTest {
    private static final Path REPORT = Path.of("build", "issue55", "microbatch-size-b.json");
    private final List<Issue55MicroBatchSizeComparison.Call> calls = new ArrayList<>();
    private final List<HttpAttempt> attempts = new ArrayList<>();
    private final AtomicInteger activeOutputCap = new AtomicInteger(128);
    private final AtomicInteger activeLogicalCall = new AtomicInteger();
    private final String startedAt = Instant.now().toString();
    private double chargedUpperUsd = Issue55MicroBatchSizeComparison.PRIOR_ESTIMATED_USD;
    private String status = "PREFLIGHT";
    private String stopReason;

    @Test
    void 자식_프로세스에서_Key_존재만_확인하며_Provider는_호출하지_않는다() {
        // given / when
        boolean present = System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank();
        // then: 값을 assertion에 전달하지 않는다.
        assertThat(present).isTrue();
        System.out.println("SIZE_B_CHILD_KEY_PRESENT=true; providerCalls=0");
    }

    @Test
    void B_고정20건을_Batch2_5_10_20_각2회_평가한다() throws IOException {
        // given: 기존 report가 있으면 자동 유료 재실행/덮어쓰기를 금지한다.
        assertThat(Files.exists(REPORT)).as("Existing size B report; do not automatically repeat paid calls").isFalse();
        var forecast = Issue55MicroBatchSizeComparison.forecast();
        double planned = forecast.stream().mapToDouble(f -> f.noCacheEstimateAtCapUsd()).sum();
        double four = forecast.stream().mapToDouble(f -> f.allFourAttemptsEstimateUsd()).sum();
        assertThat(chargedUpperUsd + four).isLessThan(Issue55MicroBatchComparison.LIMIT_USD);
        String key = System.getenv("OPENAI_API_KEY");
        assertThat(key != null && !key.isBlank()).isTrue();
        System.out.printf(Locale.ROOT,
                "SIZE_B_PREFLIGHT calls=34 priorUsd=%.8f estimatedInputPlusOutputCapUsd=%.8f allFourAttemptsUsd=%.8f limitUsd=0.50%n",
                chargedUpperUsd, planned, four);
        save();
        OpenAiHttpClientBuilderCustomizer customizer = http -> http.interceptor(chain -> {
            double reservation = Issue55MicroBatchComparison.cost(128_000, activeOutputCap.get(), 0);
            if (chargedUpperUsd + reservation > Issue55MicroBatchComparison.LIMIT_USD) {
                stopReason = "BUDGET_LIMIT";
                throw new IllegalStateException("ISSUE55_BUDGET_LIMIT");
            }
            chargedUpperUsd += reservation;
            long started = System.nanoTime();
            try {
                var response = chain.proceed(chain.request());
                BatchUsage usage = null;
                String errorCode = null;
                try {
                    // 메모리에서 Usage/error code만 취한다. 원문/응답 본문/인증 header는 저장하지 않는다.
                    JsonNode body = Issue55MicroBatchEvaluation.JSON.readTree(response.peekBody(1_000_000).string());
                    JsonNode u = body.path("usage");
                    if (u.path("prompt_tokens").isIntegralNumber() && u.path("completion_tokens").isIntegralNumber()
                            && u.path("total_tokens").isIntegralNumber()) {
                        Long cached = u.path("prompt_tokens_details").path("cached_tokens").isIntegralNumber()
                                ? u.path("prompt_tokens_details").path("cached_tokens").asLong() : null;
                        usage = new BatchUsage(u.path("prompt_tokens").asLong(), u.path("completion_tokens").asLong(),
                                u.path("total_tokens").asLong(), cached);
                    }
                    String code = body.path("error").path("code").asText("");
                    if (code.matches("[a-zA-Z0-9_]{1,80}")) {
                        errorCode = code;
                    }
                } catch (RuntimeException ignored) {
                    errorCode = "unclassified_response";
                }
                // Usage가 없거나 잘못되면 예약액을 유지한다. Cache 할인은 예산 상한에 사용하지 않는다.
                boolean valid = validUsage(usage);
                double charged = valid ? Issue55MicroBatchComparison.cost(usage.prompt(), usage.completion(), 0) : reservation;
                chargedUpperUsd += charged - reservation;
                attempts.add(new HttpAttempt(attempts.size() + 1, activeLogicalCall.get(), response.code(), errorCode,
                        usage, charged, System.nanoTime() - started));
                return response;
            } catch (IOException network) {
                attempts.add(new HttpAttempt(attempts.size() + 1, activeLogicalCall.get(), null, "network_error",
                        null, reservation, System.nanoTime() - started));
                throw network;
            }
        });
        OpenAIClient sync = OpenAiSetup.setupSyncClient("https://api.openai.com/v1", key, null, null,
                null, null, false, false, Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(90),
                3, null, Map.of(), ObservationRegistry.NOOP, null, List.of(customizer));
        OpenAIClientAsync async = OpenAiSetup.setupAsyncClient("https://api.openai.com/v1", key, null, null,
                null, null, false, false, Issue55MicroBatchEvaluation.MODEL, Duration.ofSeconds(90),
                3, null, Map.of(), ObservationRegistry.NOOP, null, List.of(customizer));
        try {
            ChatClient client = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sync).openAiClientAsync(async)
                    .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).build()).build()).build();
            status = "RUNNING";
            for (var step : Issue55MicroBatchSizeComparison.schedule()) {
                var batch = Issue55MicroBatchSizeComparison.batches(step.batchSize()).get(step.batchIndex());
                long started = System.nanoTime();
                String callStartedAt = Instant.now().toString();
                int attemptStart = attempts.size();
                activeOutputCap.set(batch.outputCap());
                activeLogicalCall.incrementAndGet();
                // when: 동일한 Batch 계약과 F0 분할/순서를 두 번 사용한다.
                var result = Issue55MicroBatchEvaluation.evaluate(client, batch);
                calls.add(new Issue55MicroBatchSizeComparison.Call(step,
                        Issue55MicroBatchSizeComparison.requestHash(batch), callStartedAt,
                        System.nanoTime() - started, attempts.size() - attemptStart, result));
                if (stopReason != null || result.failure() != null || !validUsage(result.usage())
                        || !"gpt-4o-mini-2024-07-18".equals(result.model())) {
                    if (stopReason == null) {
                        stopReason = result.failure() != null ? "RESPONSE_CONTRACT_" + result.failure()
                                : !validUsage(result.usage()) ? "INVALID_USAGE" : "MODEL_CHANGED";
                    }
                    status = "STOPPED";
                }
                save();
                if ("STOPPED".equals(status)) {
                    throw new AssertionError("Size B stopped: " + stopReason + "; partial report saved");
                }
                if (calls.size() % 10 == 0) {
                    System.out.printf(Locale.ROOT, "SIZE_B_PROGRESS repeat=%d logical=%d/34 http=%d cumulativeUpperUsd=%.8f%n",
                            step.repeat(), calls.size(), attempts.size(), chargedUpperUsd);
                }
            }
            // then: 실행 계약 통과와 품질 Guardrail 판정은 구분한다. 낮은 품질을 재실행해 덮어쓰지 않는다.
            assertThat(calls).hasSize(34).allSatisfy(c -> {
                assertThat(c.response().inputCount()).isEqualTo(c.step().batchSize());
                assertThat(c.response().cases()).hasSize(c.step().batchSize());
            });
            assertThat(activeLogicalCall.get()).isEqualTo(34);
            assertThat(calls.stream().flatMap(c -> c.response().cases().stream()).count()).isEqualTo(160);
            assertThat(Issue55MicroBatchSizeComparison.groups(calls)).hasSize(8);
            assertThat(Issue55MicroBatchSizeComparison.series(calls)).hasSize(80);
            assertThat(Issue55MicroBatchSizeComparison.comparisons(calls)).hasSize(12);
            assertThat(chargedUpperUsd).isLessThan(.50);
            status = "COMPLETED";
            save();
        } finally {
            sync.close();
            async.close();
        }
    }

    private static boolean validUsage(BatchUsage usage) {
        return usage != null && usage.prompt() >= 0 && usage.completion() >= 0
                && usage.total() == usage.prompt() + usage.completion()
                && (usage.cachedInput() == null || (usage.cachedInput() >= 0 && usage.cachedInput() <= usage.prompt()));
    }

    private void save() throws IOException {
        Files.createDirectories(REPORT.getParent());
        var hashes = Map.of("singleSystemPrompt", Issue55MicroBatchF0.sha256(ModerationPrompt.SYSTEM_PROMPT),
                "batchSystemPrompt", Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.SYSTEM_PROMPT),
                "singleSchema", Issue55MicroBatchF0.sha256(Issue55MicroBatchComparison.SINGLE_CONVERTER.getJsonSchema()),
                "batchSchema", Issue55MicroBatchF0.sha256(Issue55MicroBatchEvaluation.CONVERTER.getJsonSchema()));
        var snapshot = new Snapshot(startedAt, Instant.now().toString(), status, stopReason,
                "339423fbca943d578bf07793ff336490904290eb + uncommitted test source", Issue55MicroBatchEvaluation.MODEL,
                ModerationPrompt.PROMPT_VERSION, ModerationPrompt.POLICY_VERSION, 55L,
                "B only; sampling/provider seed unspecified; SDK maxRetries=3; timeout=90s; cap=128*actual items; fixed F0 membership/order",
                hashes, Issue55MicroBatchF0.manifest(), Issue55MicroBatchSizeComparison.schedule(),
                Issue55MicroBatchSizeComparison.forecast(), Issue55MicroBatchSizeComparison.PRIOR_ESTIMATED_USD,
                chargedUpperUsd, List.copyOf(attempts), List.copyOf(calls), Issue55MicroBatchSizeComparison.groups(calls),
                Issue55MicroBatchSizeComparison.comparisons(calls), Issue55MicroBatchSizeComparison.series(calls));
        Files.writeString(REPORT, Issue55MicroBatchEvaluation.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    }

    record HttpAttempt(int number, int logicalCall, Integer status, String errorCode, BatchUsage usage,
                       double chargedUpperUsd, long nanos) { }
    record Snapshot(String startedAt, String recordedAt, String status, String stopReason, String head,
                    String configuredModel, String promptVersion, String policyVersion, long datasetSeed, String options,
                    Map<String, String> hashes, List<Issue55MicroBatchF0.Manifest> manifest,
                    List<Issue55MicroBatchSizeComparison.Step> schedule, List<Issue55MicroBatchComparison.Forecast> forecast,
                    double priorEstimatedUsd, double chargedCumulativeUpperUsd, List<HttpAttempt> httpAttempts,
                    List<Issue55MicroBatchSizeComparison.Call> calls, List<Issue55MicroBatchSizeComparison.Group> groups,
                    List<Issue55MicroBatchSizeComparison.Comparison> comparisons, List<Issue55MicroBatchSizeComparison.Series> series) { }
}
