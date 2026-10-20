package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.infrastructure.ai.Issue55MicroBatchEvaluation.BatchObservation;
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

// 지정 메서드만 실행하는 유료 Smoke다. Spring Context/Kafka/DB는 기동하지 않는다.
@Tag("openai-evaluation")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class Issue55MicroBatchOpenAiSmokeTest {
    private static final int MAX_RETRIES = 3;
    private static final int INPUT_CEILING = 128_000;
    private static final double BUDGET_USD = 0.50;
    private static final Path REPORT = Path.of("build", "issue55", "microbatch-smoke.json");

    @Test
    void Gradle_테스트_프로세스에서_Key_존재만_확인한다() {
        // given / when: 값 또는 길이를 assertion 메시지와 로그에 넣지 않는다.
        boolean present = keyPresent();
        // then
        assertThat(present).isTrue();
        System.out.println("ISSUE55_TEST_PROCESS_KEY_PRESENT=true; providerCalls=0");
    }

    @Test
    void Batch_2_5_10을_한번씩만_호출하고_실패시_즉시_중단한다() throws IOException {
        // given: cap과 Retry를 포함한 최악의 비용을 예약한다. 실제 Usage/청구액이 아니다.
        double upperBound = List.of(2, 5, 10).stream().mapToDouble(size ->
                estimateUsd(INPUT_CEILING, 128L * size) * (MAX_RETRIES + 1)).sum();
        assertThat(upperBound).isLessThan(BUDGET_USD);
        assertThat(keyPresent()).isTrue();
        System.out.printf(Locale.ROOT,
                "SMOKE_BUDGET maxLogicalCalls=3 maxHttpAttempts=12 upperBoundUsd=%.7f budgetUsd=%.2f%n",
                upperBound, BUDGET_USD);
        List<SmokeObservation> observations = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> statuses = new ArrayList<>();
        List<String> errorCodes = new ArrayList<>();
        String key = System.getenv("OPENAI_API_KEY");
        OpenAiHttpClientBuilderCustomizer customizer = http ->
                http.interceptor(chain -> {
                    attempts.incrementAndGet();
                    var response = chain.proceed(chain.request());
                    statuses.add(response.code());
                    if (!response.isSuccessful()) {
                        // HTTP 오류 본문·요청·Authorization 대신 분류용 code만 기록한다.
                        try {
                            var error = Issue55MicroBatchEvaluation.JSON.readTree(response.peekBody(16_384).string())
                                    .path("error");
                            String code = error.path("code").asText("");
                            if (code.matches("[a-zA-Z0-9_]{1,80}")) {
                                errorCodes.add(code);
                            }
                        } catch (RuntimeException ignored) {
                            errorCodes.add("unclassified_http_error");
                        }
                    }
                    return response;
                });
        OpenAIClient sync = OpenAiSetup.setupSyncClient("https://api.openai.com/v1", key,
                null, null, null, null, false, false, Issue55MicroBatchEvaluation.MODEL,
                Duration.ofSeconds(90), MAX_RETRIES, null, Map.of(), ObservationRegistry.NOOP, null,
                List.of(customizer));
        OpenAIClientAsync async = OpenAiSetup.setupAsyncClient("https://api.openai.com/v1", key,
                     null, null, null, null, false, false, Issue55MicroBatchEvaluation.MODEL,
                     Duration.ofSeconds(90), MAX_RETRIES, null, Map.of(), ObservationRegistry.NOOP, null,
                     List.of(customizer));
        try {
            ChatClient client = ChatClient.builder(OpenAiChatModel.builder().openAiClient(sync)
                    .openAiClientAsync(async)
                    .options(OpenAiChatOptions.builder().model(Issue55MicroBatchEvaluation.MODEL).build())
                    .build()).build();
            // when: Seed 55의 첫 Batch로 크기를 순차 증가한다. 전체 Dataset 평가는 실행하지 않는다.
            for (int size : List.of(2, 5, 10)) {
                var batch = Issue55MicroBatchEvaluation.plan(size).batches().get(0);
                int beforeAttempts = attempts.get();
                int beforeStatuses = statuses.size();
                int beforeErrors = errorCodes.size();
                BatchObservation result = Issue55MicroBatchEvaluation.evaluate(client, batch);
                var usage = result.usage();
                Double estimatedCost = usage == null ? null : estimateUsd(usage.prompt(), usage.completion());
                var observation = new SmokeObservation(size, attempts.get() - beforeAttempts,
                        List.copyOf(statuses.subList(beforeStatuses, statuses.size())),
                        List.copyOf(errorCodes.subList(beforeErrors, errorCodes.size())),
                        result, estimatedCost);
                observations.add(observation);
                Files.createDirectories(REPORT.getParent());
                Files.writeString(REPORT, Issue55MicroBatchEvaluation.JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new SmokeReport(Instant.now().toString(),
                                Issue55MicroBatchEvaluation.MODEL, ModerationPrompt.PROMPT_VERSION,
                                ModerationPrompt.POLICY_VERSION, Issue55MicroBatchEvaluation.SEED,
                                upperBound, List.copyOf(observations))));
                System.out.printf(Locale.ROOT,
                        "SMOKE batch=%d httpAttempts=%d statuses=%s failure=%s input=%s output=%s total=%s latencyMs=%.1f estimatedCostUsd=%s%n",
                        size, observation.httpAttempts(), observation.httpStatuses(), result.failure(),
                        usage == null ? "unknown" : usage.prompt(), usage == null ? "unknown" : usage.completion(),
                        usage == null ? "unknown" : usage.total(), result.callNanos() / 1_000_000.0,
                        estimatedCost == null ? "unknown" : String.format(Locale.ROOT, "%.8f", estimatedCost));
                // then: Schema/parse/ID/Validation/잘림 실패는 다음 크기로 진행하지 않는다.
                assertThat(result.failure()).as("Batch %d failure stage", size).isNull();
                assertThat(result.mapping()).isNotNull();
                assertThat(result.mapping().invalid()).isFalse();
                assertThat(result.cases()).hasSize(size).allSatisfy(c -> {
                    assertThat(c.failure()).isNull();
                    assertThat(c.actual()).isNotNull();
                });
                assertThat(usage).as("Provider Usage required").isNotNull();
                assertThat(usage.total()).isEqualTo(usage.prompt() + usage.completion());
                assertThat(usage.prompt()).isPositive();
                assertThat(usage.completion()).isPositive();
                assertThat(attempts.get()).isLessThanOrEqualTo(observations.size() * (MAX_RETRIES + 1));
                assertThat(observations.stream().mapToDouble(o -> o.estimatedCostUsd()).sum())
                        .isLessThan(BUDGET_USD);
            }
        } finally {
            sync.close();
            async.close();
        }
    }

    private static boolean keyPresent() {
        String key = System.getenv("OPENAI_API_KEY");
        return key != null && !key.isBlank();
    }

    private static double estimateUsd(long input, long output) {
        // 공개 Standard 단가다. Cached 할인을 차감하지 않은 보수적 Estimated Cost다.
        return input * 0.15 / 1_000_000.0 + output * 0.60 / 1_000_000.0;
    }

    record SmokeObservation(int batchSize, int httpAttempts, List<Integer> httpStatuses,
                            List<String> errorCodes, BatchObservation observation, Double estimatedCostUsd) { }

    record SmokeReport(String recordedAt, String configuredModel, String promptVersion, String policyVersion,
                       long seed, double reservedUpperBoundUsd, List<SmokeObservation> batches) { }
}
