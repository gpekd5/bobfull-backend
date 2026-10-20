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
class Issue55MicroBatchF0OpenAiTest {
    private static final Path REPORT = Path.of("build", "issue55", "microbatch-f0.json");
    private final List<Issue55MicroBatchF0.Run> runs = new ArrayList<>();
    private final List<HttpAttempt> attempts = new ArrayList<>();
    private final AtomicInteger activeOutputCap = new AtomicInteger(128);
    private final AtomicInteger activeLogicalCall = new AtomicInteger();
    private final String startedAt = Instant.now().toString();
    private double chargedUpperUsd = Issue55MicroBatchF0.PRIOR_ESTIMATED_USD;
    private String status = "PREFLIGHT";
    private String stopReason;

    @Test
    void 자식_프로세스에서_Key_존재만_확인하며_Provider는_호출하지_않는다() {
        // given / when
        boolean present = System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank();
        // then: 값을 assertion에 전달하지 않는다.
        assertThat(present).isTrue();
        System.out.println("F0_CHILD_KEY_PRESENT=true; providerCalls=0");
    }

    @Test
    void F0_고정20건을_단건_Batch5_Batch10_각3회_교차실행한다() throws IOException {
        // given: 기존 report가 있으면 자동 유료 재실행/덮어쓰기를 금지한다.
        assertThat(Files.exists(REPORT)).as("Existing F0 report; do not automatically repeat paid calls").isFalse();
        var forecast = Issue55MicroBatchF0.forecast();
        double planned = forecast.stream().mapToDouble(f -> f.noCacheEstimateAtCapUsd()).sum();
        double four = forecast.stream().mapToDouble(f -> f.allFourAttemptsEstimateUsd()).sum();
        assertThat(chargedUpperUsd + four).isLessThan(Issue55MicroBatchComparison.LIMIT_USD);
        String key = System.getenv("OPENAI_API_KEY");
        assertThat(key != null && !key.isBlank()).isTrue();
        System.out.printf(Locale.ROOT,
                "F0_PREFLIGHT calls=78 priorUsd=%.8f estimatedInputPlusOutputCapUsd=%.8f allFourAttemptsUsd=%.8f limitUsd=0.50%n",
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
            for (var treatment : Issue55MicroBatchF0.schedule()) {
                long started = System.nanoTime();
                int attemptStart = attempts.size();
                List<BatchObservation> calls = new ArrayList<>();
                runs.add(new Issue55MicroBatchF0.Run(treatment, false, List.of(), null));
                var batches = Issue55MicroBatchF0.batches(treatment.batchSize());
                for (int i = 0; i < batches.size(); i++) {
                    var batch = batches.get(i);
                    activeOutputCap.set(batch.outputCap());
                    activeLogicalCall.incrementAndGet();
                    // when: 단건/Batch 평가 구현, Prompt, Schema, cap, sampling 옵션을 그대로 재사용한다.
                    var result = treatment.batchSize() == 1
                            ? Issue55MicroBatchComparison.single(client, batch.targets().get(0), i + 1)
                            : Issue55MicroBatchEvaluation.evaluate(client, batch);
                    calls.add(result);
                    if (stopReason != null || result.failure() != null || !validUsage(result.usage())
                            || !"gpt-4o-mini-2024-07-18".equals(result.model())) {
                        if (stopReason == null) {
                            stopReason = result.failure() != null ? "RESPONSE_CONTRACT_" + result.failure()
                                    : !validUsage(result.usage()) ? "INVALID_USAGE" : "MODEL_CHANGED";
                        }
                        status = "STOPPED";
                    }
                    runs.set(runs.size() - 1, new Issue55MicroBatchF0.Run(treatment, false, List.copyOf(calls), null));
                    save();
                    if ("STOPPED".equals(status)) {
                        throw new AssertionError("F0 stopped: " + stopReason + "; partial report saved");
                    }
                    if ((i + 1) % 10 == 0 || i + 1 == batches.size()) {
                        System.out.printf(Locale.ROOT, "F0_PROGRESS repeat=%d size=%d calls=%d/%d logical=%d http=%d cumulativeUpperUsd=%.8f%n",
                                treatment.repeat(), treatment.batchSize(), i + 1, batches.size(), activeLogicalCall.get(),
                                attempts.size(), chargedUpperUsd);
                    }
                }
                var stats = Issue55MicroBatchF0.stats(calls, attempts.size() - attemptStart, System.nanoTime() - started);
                runs.set(runs.size() - 1, new Issue55MicroBatchF0.Run(treatment, true, List.copyOf(calls), stats));
                save();
                System.out.printf(Locale.ROOT, "F0_RESULT repeat=%d size=%d result=%d/20 category=%d/20 risk=%d/20 FP=%d FN=%d input=%d output=%d cached=%d policyViolations=%d%n",
                        treatment.repeat(), treatment.batchSize(), stats.quality().resultMatches(), stats.quality().categoryMatches(),
                        stats.quality().riskMatches(), stats.quality().confusion().falsePositive(), stats.quality().confusion().falseNegative(),
                        stats.inputTokens(), stats.outputTokens(), stats.cachedInputTokens(), stats.policyViolationIds().size());
            }
            // then: 실행 계약 통과와 품질 Guardrail 판정은 구분한다. 낮은 품질을 재실행해 덮어쓰지 않는다.
            assertThat(runs).hasSize(9).allSatisfy(r -> assertThat(r.complete()).isTrue());
            assertThat(activeLogicalCall.get()).isEqualTo(78);
            assertThat(runs.stream().flatMap(r -> r.calls().stream()).flatMap(c -> c.cases().stream()).count()).isEqualTo(180);
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
                "F0 only; provider sampling/seed unspecified; SDK maxRetries=3; timeout=90s; cap=128*items",
                hashes, Issue55MicroBatchF0.manifest(), Issue55MicroBatchF0.schedule(), Issue55MicroBatchF0.forecast(),
                Issue55MicroBatchF0.PRIOR_ESTIMATED_USD, chargedUpperUsd, List.copyOf(attempts), List.copyOf(runs),
                Issue55MicroBatchF0.paired(runs), Issue55MicroBatchF0.variability(runs));
        Files.writeString(REPORT, Issue55MicroBatchEvaluation.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    }

    record HttpAttempt(int number, int logicalCall, Integer status, String errorCode, BatchUsage usage,
                       double chargedUpperUsd, long nanos) { }
    record Snapshot(String startedAt, String recordedAt, String status, String stopReason, String head,
                    String configuredModel, String promptVersion, String policyVersion, long datasetSeed, String options,
                    Map<String, String> hashes, List<Issue55MicroBatchF0.Manifest> manifest,
                    List<Issue55MicroBatchF0.Treatment> schedule, List<Issue55MicroBatchComparison.Forecast> forecast,
                    double priorEstimatedUsd, double chargedCumulativeUpperUsd, List<HttpAttempt> httpAttempts,
                    List<Issue55MicroBatchF0.Run> runs, List<Issue55MicroBatchF0.Paired> paired,
                    List<Issue55MicroBatchF0.Variability> variability) { }
}
