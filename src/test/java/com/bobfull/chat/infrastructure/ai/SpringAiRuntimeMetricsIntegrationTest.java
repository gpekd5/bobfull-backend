package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:spring-ai-runtime-metrics;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.ai.openai.api-key=fake-openai-key",
        "spring.ai.openai.chat.model=gpt-4o-mini",
        "management.health.redis.enabled=false",
        "management.health.mail.enabled=false",
        "management.endpoint.prometheus.access=read-only",
        "management.endpoints.web.exposure.include=health,prometheus",
        "jwt.secret=spring-ai-runtime-metrics-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=spring-ai-runtime-metrics-test-api-secret",
        "portone.store-id=spring-ai-runtime-metrics-test-store-id",
        "portone.webhook-secret=c3ByaW5nLWFpLXJ1bnRpbWUtbWV0cmljcy10ZXN0",
        "payment.expiration.enabled=false",
        "payment.refund-reconciliation.enabled=false",
        "reservation.recruitment-deadline.enabled=false",
        "reservation.dining-end.enabled=false",
        "outbox.chat-room.enabled=false",
        "outbox.email.enabled=false",
        "outbox.chat-message.enabled=false",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "bobfull.kafka.restaurant-insight.consumer-enabled=false"
})
class SpringAiRuntimeMetricsIntegrationTest {

    private static final FakeOpenAiEndpoint FAKE_OPEN_AI = FakeOpenAiEndpoint.start();

    @LocalServerPort
    private int applicationPort;

    @Autowired
    private SpringAiModerationAdapter moderationAdapter;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OpenAiCommonProperties openAiProperties;

    @DynamicPropertySource
    static void fakeOpenAiEndpoint(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", FAKE_OPEN_AI::baseUrl);
    }

    @AfterAll
    static void stopFakeOpenAiEndpoint() {
        FAKE_OPEN_AI.close();
    }

    @Test
    void 정상호출과_429_Retry호출에서_논리호출과_HTTP_attempt_Metric을_구분한다() throws Exception {
        // given
        assertThat(openAiProperties.getMaxRetries()).isEqualTo(3);
        MetricSnapshot before = snapshot();
        int attemptsBeforeNormal = FAKE_OPEN_AI.attemptCount();
        FAKE_OPEN_AI.enqueueSuccess();

        // when
        AiModerationResult normalResult = moderationAdapter.analyze("내일 7시에 식당에서 봐요");
        MetricSnapshot afterNormal = snapshot();

        // then
        assertThat(normalResult.result().result()).isEqualTo(ModerationResultType.SAFE);
        assertThat(FAKE_OPEN_AI.attemptCount() - attemptsBeforeNormal).isEqualTo(1);
        assertThat(afterNormal.chatClientCalls() - before.chatClientCalls()).isEqualTo(1);
        assertThat(afterNormal.chatModelCalls() - before.chatModelCalls()).isEqualTo(1);
        assertThat(afterNormal.httpAttempts() - before.httpAttempts()).isEqualTo(1);
        assertThat(afterNormal.inputTokens() - before.inputTokens()).isEqualTo(11);
        assertThat(afterNormal.outputTokens() - before.outputTokens()).isEqualTo(7);
        assertThat(afterNormal.totalTokens() - before.totalTokens()).isEqualTo(18);
        assertThat(afterNormal.chatClientTimeNanos() - before.chatClientTimeNanos()).isPositive();
        assertThat(afterNormal.chatModelTimeNanos() - before.chatModelTimeNanos()).isPositive();
        assertThat(afterNormal.httpTimeNanos() - before.httpTimeNanos()).isPositive();

        // given
        FAKE_OPEN_AI.enqueueRateLimitThenSuccess();
        int attemptsBeforeRetryScenario = FAKE_OPEN_AI.attemptCount();

        // when
        AiModerationResult retryResult = moderationAdapter.analyze("내일 8시에 식당에서 봐요");
        MetricSnapshot afterRetry = snapshot();

        // then
        assertThat(retryResult.result().result()).isEqualTo(ModerationResultType.SAFE);
        assertThat(FAKE_OPEN_AI.attemptCount() - attemptsBeforeRetryScenario).isEqualTo(2);
        assertThat(afterRetry.chatClientCalls() - afterNormal.chatClientCalls()).isEqualTo(1);
        assertThat(afterRetry.chatModelCalls() - afterNormal.chatModelCalls()).isEqualTo(1);
        assertThat(afterRetry.httpAttempts() - afterNormal.httpAttempts()).isEqualTo(2);
        assertThat(afterRetry.inputTokens() - afterNormal.inputTokens()).isEqualTo(11);
        assertThat(afterRetry.outputTokens() - afterNormal.outputTokens()).isEqualTo(7);
        assertThat(afterRetry.totalTokens() - afterNormal.totalTokens()).isEqualTo(18);
        assertThat(afterRetry.chatClientTimeNanos() - afterNormal.chatClientTimeNanos()).isPositive();
        assertThat(afterRetry.chatModelTimeNanos() - afterNormal.chatModelTimeNanos()).isPositive();
        assertThat(afterRetry.httpTimeNanos() - afterNormal.httpTimeNanos()).isPositive();

        String prometheus = prometheusOutput();
        assertThat(prometheus).contains("spring_ai_chat_client_seconds_count");
        assertThat(prometheus).contains("gen_ai_client_operation_seconds_count");
        assertThat(prometheus).contains("gen_ai_client_token_usage_total");
        assertThat(prometheus).contains("okhttp_requests_seconds_count");

        printRuntimeEvidence(before, afterNormal, afterRetry, prometheus);
    }

    @Test
    void OpenAI_Retry를_소진하면_provider_failure를_한번만_기록한다() {
        // given
        double providerBefore = failureCount("provider");
        double parseBefore = failureCount("parse");
        int attemptsBefore = FAKE_OPEN_AI.attemptCount();
        FAKE_OPEN_AI.enqueueRateLimitExhaustion(openAiProperties.getMaxRetries() + 1);

        // when & then
        assertThatThrownBy(() -> moderationAdapter.analyze("Provider 최종 실패 검증"))
                .isInstanceOf(com.openai.errors.OpenAIException.class);
        assertThat(FAKE_OPEN_AI.attemptCount() - attemptsBefore)
                .isEqualTo(openAiProperties.getMaxRetries() + 1);
        assertThat(failureCount("provider") - providerBefore).isEqualTo(1.0);
        assertThat(failureCount("parse") - parseBefore).isZero();
    }

    @Test
    void Structured_Output_변환이_실패하면_parse_failure를_한번만_기록한다() {
        // given
        double parseBefore = failureCount("parse");
        double providerBefore = failureCount("provider");
        FAKE_OPEN_AI.enqueueMalformedSuccess();

        // when & then
        assertThatThrownBy(() -> moderationAdapter.analyze("Structured Output 변환 실패 검증"))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThat(failureCount("parse") - parseBefore).isEqualTo(1.0);
        assertThat(failureCount("provider") - providerBefore).isZero();
    }

    private MetricSnapshot snapshot() {
        return new MetricSnapshot(
                timerCount("spring.ai.chat.client"),
                timerCount("gen_ai.client.operation"),
                timerCount("okhttp.requests"),
                tokenCount("input"),
                tokenCount("output"),
                tokenCount("total"),
                timerTotalNanos("spring.ai.chat.client"),
                timerTotalNanos("gen_ai.client.operation"),
                timerTotalNanos("okhttp.requests"));
    }

    private long timerCount(String name) {
        return Math.round(meterRegistry.find(name).timers().stream().mapToDouble(Timer::count).sum());
    }

    private long tokenCount(String tokenType) {
        return Math.round(meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.token.type", tokenType)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum());
    }

    private long timerTotalNanos(String name) {
        return Math.round(meterRegistry.find(name).timers().stream()
                .mapToDouble(timer -> timer.totalTime(TimeUnit.NANOSECONDS))
                .sum());
    }

    private double failureCount(String stage) {
        Counter counter = meterRegistry.find("bobfull.ai.moderation.failure")
                .tags(
                        "stage", stage,
                        "prompt_version", ModerationPrompt.PROMPT_VERSION,
                        "policy_version", ModerationPrompt.POLICY_VERSION)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String prometheusOutput() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + applicationPort + "/actuator/prometheus"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private void printRuntimeEvidence(
            MetricSnapshot before,
            MetricSnapshot afterNormal,
            MetricSnapshot afterRetry,
            String prometheus) {
        System.out.printf("%n===== SPRING AI RUNTIME METRIC DELTA =====%n");
        System.out.printf("normal=%s%n", afterNormal.minus(before));
        System.out.printf("retry=%s%n", afterRetry.minus(afterNormal));
        System.out.printf("fakeProviderTotalHttpAttempts=%d%n", FAKE_OPEN_AI.attemptCount());

        System.out.printf("%n===== SPRING AI RUNTIME METERS =====%n");
        meterRegistry.getMeters().stream()
                .filter(meter -> isTargetMeter(meter.getId().getName()))
                .sorted(Comparator.comparing(meter -> meter.getId().toString()))
                .forEach(meter -> System.out.printf("type=%s id=%s measurements=%s%n",
                        meter.getId().getType(), meter.getId(), measurements(meter)));

        System.out.printf("%n===== PROMETHEUS EXPORTED SERIES =====%n");
        prometheus.lines()
                .filter(line -> line.startsWith("spring_ai_chat_client")
                        || line.startsWith("gen_ai_client_operation")
                        || line.startsWith("gen_ai_client_token_usage")
                        || line.startsWith("okhttp_requests"))
                .forEach(System.out::println);
    }

    private static boolean isTargetMeter(String name) {
        return name.equals("spring.ai.chat.client")
                || name.equals("gen_ai.client.operation")
                || name.equals("gen_ai.client.token.usage")
                || name.equals("okhttp.requests");
    }

    private static List<String> measurements(Meter meter) {
        List<String> values = new ArrayList<>();
        meter.measure().forEach(measurement -> values.add(
                measurement.getStatistic().name() + "=" + measurement.getValue()));
        return values;
    }

    private record MetricSnapshot(
            long chatClientCalls,
            long chatModelCalls,
            long httpAttempts,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long chatClientTimeNanos,
            long chatModelTimeNanos,
            long httpTimeNanos) {

        MetricSnapshot minus(MetricSnapshot before) {
            return new MetricSnapshot(
                    chatClientCalls - before.chatClientCalls,
                    chatModelCalls - before.chatModelCalls,
                    httpAttempts - before.httpAttempts,
                    inputTokens - before.inputTokens,
                    outputTokens - before.outputTokens,
                    totalTokens - before.totalTokens,
                    chatClientTimeNanos - before.chatClientTimeNanos,
                    chatModelTimeNanos - before.chatModelTimeNanos,
                    httpTimeNanos - before.httpTimeNanos);
        }
    }

    private static final class FakeOpenAiEndpoint implements AutoCloseable {

        private static final String SUCCESS_BODY = """
                {
                  "id": "chatcmpl-fake-observability",
                  "object": "chat.completion",
                  "created": 1720000000,
                  "model": "gpt-4o-mini-fake",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "{\\\"result\\\":\\\"SAFE\\\",\\\"categories\\\":[],\\\"riskLevel\\\":\\\"LOW\\\"}",
                        "refusal": null,
                        "annotations": []
                      },
                      "logprobs": null,
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 11,
                    "completion_tokens": 7,
                    "total_tokens": 18
                  }
                }
                """;

        private static final String RATE_LIMIT_BODY = """
                {
                  "error": {
                    "message": "fake rate limit",
                    "type": "rate_limit_error",
                    "param": null,
                    "code": "rate_limit_exceeded"
                  }
                }
                """;

        private static final String MALFORMED_SUCCESS_BODY = """
                {
                  "id": "chatcmpl-fake-malformed",
                  "object": "chat.completion",
                  "created": 1720000000,
                  "model": "gpt-4o-mini-fake",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "not-json",
                        "refusal": null,
                        "annotations": []
                      },
                      "logprobs": null,
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 11,
                    "completion_tokens": 2,
                    "total_tokens": 13
                  }
                }
                """;

        private final HttpServer server;
        private final Queue<ResponsePlan> responses = new ConcurrentLinkedQueue<>();
        private final AtomicInteger attemptCount = new AtomicInteger();

        private FakeOpenAiEndpoint(HttpServer server) {
            this.server = server;
        }

        static FakeOpenAiEndpoint start() {
            try {
                HttpServer server = HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                FakeOpenAiEndpoint endpoint = new FakeOpenAiEndpoint(server);
                server.createContext("/v1/chat/completions", endpoint::handle);
                server.start();
                return endpoint;
            } catch (IOException exception) {
                throw new IllegalStateException("Fake OpenAI endpoint를 시작하지 못했습니다.", exception);
            }
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort() + "/v1";
        }

        void enqueueSuccess() {
            responses.add(new ResponsePlan(200, SUCCESS_BODY));
        }

        void enqueueRateLimitThenSuccess() {
            responses.add(new ResponsePlan(429, RATE_LIMIT_BODY));
            responses.add(new ResponsePlan(200, SUCCESS_BODY));
        }

        void enqueueRateLimitExhaustion(int attempts) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                responses.add(new ResponsePlan(429, RATE_LIMIT_BODY));
            }
        }

        void enqueueMalformedSuccess() {
            responses.add(new ResponsePlan(200, MALFORMED_SUCCESS_BODY));
        }

        int attemptCount() {
            return attemptCount.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            attemptCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            ResponsePlan response = responses.poll();
            if (response == null) {
                response = new ResponsePlan(500, "{\"error\":{\"message\":\"no fake response\"}}");
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (response.status() == 429) {
                exchange.getResponseHeaders().set("Retry-After", "0");
            }
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record ResponsePlan(int status, String body) {
        }
    }
}
