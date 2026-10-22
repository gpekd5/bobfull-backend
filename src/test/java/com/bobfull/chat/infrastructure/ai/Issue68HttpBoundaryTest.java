package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;

class Issue68HttpBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void 실제_SDK_429_Retry는_논리1_HTTP2로_각각_예약한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("retry.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.RATE_LIMIT, Issue68FakeOpenAiEndpoint.Mode.OK);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(OpenAiCommonProperties.class).getMaxRetries()).isEqualTo(3);
                var c = Issue68EvaluationContract.diagnostic().get(0);
                var call = boundary.begin(List.of(c.id()), false);
                // when
                try {
                    var result = context.getBean(AiModerationPort.class).analyze(c.content());
                    assertThat(result.result()).isEqualTo(c.expected());
                } finally {
                    boundary.end(call, false);
                }
                // then
                assertThat(endpoint.received()).isEqualTo(2);
                assertThat(boundary.calls()).hasSize(1);
                assertThat(boundary.attempts()).extracting(Issue68HttpBoundary.Attempt::status).containsExactly(429, 200);
                assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(128000, 128) + Issue68Budget.cost(900, 20));
                assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                        .find("gen_ai.client.operation").timer()).isNotNull();
            });
        }
    }

    @Test
    void SDK_재시도_중_예산이_부족하면_두번째_HTTP는_전송하지_않는다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("stop.jsonl"), Issue68Budget.cost(128000, 128));
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.SERVER_ERROR);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                assertThat(context).hasNotFailed();
                var c = Issue68EvaluationContract.diagnostic().get(0);
                var call = boundary.begin(List.of(c.id()), false);
                // when & then
                assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content()))
                        .isInstanceOf(RuntimeException.class);
                boundary.end(call, true);
                assertThat(endpoint.received()).isEqualTo(1);
                assertThat(budget.attempts()).isEqualTo(1);
                assertThat(budget.blocked()).isTrue();
            });
        }
    }

    @Test
    void 정상_Batch_Usage는_메시지별이_아닌_요청당_한번만_집계한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("batch.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), true).run(context -> {
                assertThat(context).hasNotFailed();
                var cases = Issue68EvaluationContract.diagnostic().subList(0, 10);
                var call = boundary.begin(cases.stream().map(Issue68EvaluationContract.Case::id).toList(), true);
                var items = java.util.stream.IntStream.range(0, 10).mapToObj(i -> new AiModerationBatchPort.Item(
                        "m%03d".formatted(i + 1), cases.get(i).content())).toList();
                // when
                var response = context.getBean(AiModerationBatchPort.class).analyze(items);
                boundary.end(call, false);
                // then
                assertThat(response.results()).hasSize(10);
                assertThat(boundary.attempts()).hasSize(1);
                assertThat(boundary.attempts().get(0).usage().total()).isEqualTo(1100);
                assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(900, 200));
                assertThat(call.cap).isEqualTo(1280);
            });
        }
    }

    @Test
    void redirect를_따르지_않고_Usage_불명_200은_최대액을_유지한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("unknown.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.REDIRECT, Issue68FakeOpenAiEndpoint.Mode.NO_USAGE);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var c = Issue68EvaluationContract.diagnostic().get(0);
                var first = boundary.begin(List.of(c.id()), false);
                // when
                assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content()))
                        .isInstanceOf(RuntimeException.class);
                boundary.end(first, true);
                assertThat(endpoint.received()).isEqualTo(1);
                var second = boundary.begin(List.of(c.id()), false);
                context.getBean(AiModerationPort.class).analyze(c.content());
                boundary.end(second, false);
                // then
                assertThat(endpoint.received()).isEqualTo(2);
                assertThat(budget.chargedNanos()).isEqualTo(2 * Issue68Budget.cost(128000, 128));
            });
        }
    }

    @Test
    void 실제_OpenAI_URL은_키와_무관하게_생성_단계에서_거부한다() throws Exception {
        // given
        try (var budget = new Issue68Budget(temporary.resolve("forbid.jsonl"), Issue68Budget.PLANNED_LIMIT)) {
            // when & then
            assertThatThrownBy(() -> new Issue68HttpBoundary(URI.create("https://api.openai.com/v1"), budget))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("ISSUE68_LIVE_ENDPOINT_NOT_AUTHORIZED");
            assertThat(budget.attempts()).isZero();
        }
    }

    @Test
    void 하위_503_followup도_교환마다_예약하고_예산부족시_차단한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("followup.jsonl"), Issue68Budget.cost(128000, 128));
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.RETRY_503, Issue68FakeOpenAiEndpoint.Mode.OK);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var c = Issue68EvaluationContract.diagnostic().get(0);
                var logical = boundary.begin(List.of(c.id()), false);
                // when & then
                assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content()))
                        .isInstanceOf(RuntimeException.class);
                boundary.end(logical, true);
                assertThat(endpoint.received()).isEqualTo(1);
                assertThat(boundary.attempts()).hasSize(1);
                assertThat(budget.blocked()).isTrue();
            });
        }
    }
}
