package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparison.Change;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparison.Forecast;
import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchComparison.Metrics;
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

// 143 logical calls를 순차 실행하는 독립 실험이다. 응답 계약 실패/예산 위험 시 부분 결과를 남기고 중단한다.
@Tag("openai-evaluation")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class Issue55MicroBatchComparisonOpenAiTest {
    private static final Path REPORT = Path.of("build", "issue55", "microbatch-comparison.json");
    private final List<Run> runs = new ArrayList<>();
    private final List<HttpAttempt> attempts = new ArrayList<>();
    private final AtomicInteger activeOutputCap = new AtomicInteger(128);
    private final String startedAt = Instant.now().toString();
    private double chargedUpperUsd = Issue55MicroBatchComparison.PRIOR_ESTIMATED_USD;
    private String status = "PREFLIGHT";
    private String stopReason;

    @Test
    void 단건_79건과_Batch_2_5_10을_동일입력으로_비교한다() throws IOException {
        // given: 재실행으로 과금/기존 결과를 덮어쓰지 않게 자동 재개하지 않는다.
        assertThat(Files.exists(REPORT)).as("Existing report: do not automatically repeat paid calls").isFalse();
        var forecast = Issue55MicroBatchComparison.forecast();
        double plannedAtCap = forecast.stream().mapToDouble(Forecast::noCacheEstimateAtCapUsd).sum();
        double fourAttempts = forecast.stream().mapToDouble(Forecast::allFourAttemptsEstimateUsd).sum();
        assertThat(Issue55MicroBatchComparison.PRIOR_ESTIMATED_USD + fourAttempts)
                .as("Local tokenizer forecast, not Provider accounting").isLessThan(Issue55MicroBatchComparison.LIMIT_USD);
        String key = System.getenv("OPENAI_API_KEY");
        assertThat(key != null && !key.isBlank()).isTrue();
        System.out.printf(Locale.ROOT,
                "COMPARISON_PREFLIGHT calls=143 priorUsd=%.8f inputEstimatePlusOutputCapUsd=%.8f allFourAttemptsForecastUsd=%.8f limitUsd=0.50%n",
                chargedUpperUsd, plannedAtCap, fourAttempts);
        save(forecast);
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
                    // 본문은 메모리에서 Usage만 읽는다. 원문·인증 header·오류 메시지는 저장하지 않는다.
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
                double charged = usage == null ? reservation
                        : Issue55MicroBatchComparison.cost(usage.prompt(), usage.completion(), 0);
                chargedUpperUsd += charged - reservation;
                attempts.add(new HttpAttempt(attempts.size() + 1, response.code(), errorCode, usage,
                        charged, System.nanoTime() - started));
                return response;
            } catch (IOException network) {
                attempts.add(new HttpAttempt(attempts.size() + 1, null, "network_error", null,
                        reservation, System.nanoTime() - started));
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
            var targets = Issue55MicroBatchEvaluation.plan(2).batches().stream().flatMap(b -> b.targets().stream()).toList();
            String responseModel = null;
            status = "RUNNING";
            // when: 동일 target 순서로 단건을 먼저 새로 측정한다.
            for (int size : List.of(1, 2, 5, 10)) {
                long started = System.nanoTime();
                int attemptStart = attempts.size();
                List<BatchObservation> calls = new ArrayList<>();
                runs.add(new Run(size, false, List.of(), null, List.of()));
                int count = size == 1 ? 79 : Issue55MicroBatchEvaluation.plan(size).batches().size();
                for (int i = 0; i < count; i++) {
                    activeOutputCap.set(size == 1 ? 128
                            : Issue55MicroBatchEvaluation.plan(size).batches().get(i).outputCap());
                    BatchObservation result = size == 1
                            ? Issue55MicroBatchComparison.single(client, targets.get(i), i + 1)
                            : Issue55MicroBatchEvaluation.evaluate(client, Issue55MicroBatchEvaluation.plan(size).batches().get(i));
                    calls.add(result);
                    if (responseModel == null && result.model() != null) {
                        responseModel = result.model();
                    }
                    boolean modelChanged = result.model() != null && !result.model().equals(responseModel);
                    boolean invalidUsage = result.usage() == null || result.usage().prompt() < 0
                            || result.usage().completion() < 0
                            || result.usage().total() != result.usage().prompt() + result.usage().completion()
                            || (result.usage().cachedInput() != null && (result.usage().cachedInput() < 0
                            || result.usage().cachedInput() > result.usage().prompt()));
                    if (stopReason != null || result.failure() != null || modelChanged || invalidUsage) {
                        if (stopReason == null) {
                            stopReason = result.failure() != null ? "RESPONSE_CONTRACT_" + result.failure()
                                    : modelChanged ? "MODEL_CHANGED" : "INVALID_USAGE";
                        }
                        status = "STOPPED";
                    }
                    runs.set(runs.size() - 1, new Run(size, false, List.copyOf(calls), null, List.of()));
                    save(forecast);
                    if ("STOPPED".equals(status)) {
                        throw new AssertionError("Comparison stopped: " + stopReason + "; partial report saved");
                    }
                    if ((i + 1) % 10 == 0 || i + 1 == count) {
                        System.out.printf(Locale.ROOT,
                                "COMPARISON_PROGRESS size=%d calls=%d/%d httpAttempts=%d chargedCumulativeUpperUsd=%.8f%n",
                                size, i + 1, count, attempts.size(), chargedUpperUsd);
                    }
                }
                Metrics metrics = Issue55MicroBatchComparison.metrics(calls, System.nanoTime() - started,
                        attempts.size() - attemptStart);
                List<Change> changes = size == 1 ? List.of() : Issue55MicroBatchComparison.changes(
                        runs.get(0).calls().stream().flatMap(c -> c.cases().stream()).toList(),
                        calls.stream().flatMap(c -> c.cases().stream()).toList());
                runs.set(runs.size() - 1, new Run(size, true, List.copyOf(calls), metrics, changes));
                save(forecast);
                System.out.printf(Locale.ROOT,
                        "COMPARISON_RESULT size=%d result=%d/80 category=%d/80 risk=%d/80 FP=%d FN=%d input=%d output=%d cached=%d noCacheUsd=%.8f cacheAwareUsd=%.8f wallMs=%.1f changes=%d%n",
                        size, metrics.all80().resultMatches(), metrics.all80().categoryMatches(), metrics.all80().riskMatches(),
                        metrics.all80().confusion().falsePositive(), metrics.all80().confusion().falseNegative(),
                        metrics.inputTokens(), metrics.outputTokens(), metrics.cachedInputTokens(), metrics.noCacheEstimatedUsd(),
                        metrics.cacheAwareEstimatedUsd(), metrics.datasetWallMs(), changes.size());
            }
            // then: 품질 차이는 관측 결과다. 실행 성공을 품질 Guardrail 통과로 간주하지 않는다.
            assertThat(runs).hasSize(4).allSatisfy(r -> assertThat(r.complete()).isTrue());
            assertThat(runs.stream().mapToInt(r -> r.calls().size()).sum()).isEqualTo(143);
            assertThat(chargedUpperUsd).isLessThan(Issue55MicroBatchComparison.LIMIT_USD);
            status = "COMPLETED";
            save(forecast);
        } finally {
            sync.close();
            async.close();
        }
    }

    private void save(List<Forecast> forecast) throws IOException {
        Files.createDirectories(REPORT.getParent());
        var snapshot = new Snapshot(startedAt, Instant.now().toString(), status, stopReason,
                "339423fbca943d578bf07793ff336490904290eb + uncommitted test source",
                Issue55MicroBatchEvaluation.MODEL, ModerationPrompt.PROMPT_VERSION, ModerationPrompt.POLICY_VERSION,
                Issue55MicroBatchEvaluation.SEED, forecast, Issue55MicroBatchComparison.PRIOR_ESTIMATED_USD,
                chargedUpperUsd, List.copyOf(attempts), List.copyOf(runs));
        Files.writeString(REPORT, Issue55MicroBatchEvaluation.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    }

    record HttpAttempt(int number, Integer status, String errorCode, BatchUsage usage,
                       double chargedUpperUsd, long nanos) { }
    record Run(int batchSize, boolean complete, List<BatchObservation> calls, Metrics metrics, List<Change> changes) { }
    record Snapshot(String startedAt, String recordedAt, String status, String stopReason, String head,
                    String configuredModel, String promptVersion, String policyVersion, long seed,
                    List<Forecast> forecast, double priorEstimatedUsd, double chargedCumulativeUpperUsd,
                    List<HttpAttempt> httpAttempts, List<Run> runs) { }
}
