package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Issue68UsageAccountingTest {
    @TempDir
    Path temporary;

    @Test
    void gzip_응답도_SDK와_예약장부_Usage가_일치한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("gzip.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.gzip(true);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var c = Issue68LiveSession.smokeCases().get(0);
                var call = boundary.begin(List.of(c.id()), false);
                // when: 실제 SDK가 같은 body를 정상 파싱해야 한다.
                var result = context.getBean(AiModerationPort.class).analyze(c.content());
                boundary.end(call, false);
                // then
                assertThat(result.promptTokens()).isEqualTo(900L);
                assertThat(result.completionTokens()).isEqualTo(20L);
                assertThat(result.totalTokens()).isEqualTo(920L);
                assertThat(boundary.attempts()).hasSize(1);
                assertThat(boundary.attempts().get(0).usage()).isEqualTo(new Issue68Budget.Usage(900, 20, 920, 0L));
                assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(900, 20));
            });
        }
    }

    @Test
    void 비압축_응답도_SDK와_장부의_Token이_일치한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("plain.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            // when
            nativeRequest(endpoint, boundary, response -> {
                // then
                var usage = boundary.attempts().get(0).usage();
                assertThat(usage.input()).isEqualTo(response.promptTokens());
                assertThat(usage.output()).isEqualTo(response.completionTokens());
                assertThat(usage.total()).isEqualTo(response.totalTokens());
            });
            assertThat(boundary.attempts().get(0).contentEncoding()).isEqualTo("identity");
            assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(900, 20));
        }
    }

    @Test
    void 압축_429후_성공은_HTTP별_예약과_정산을_분리한다() throws Exception {
        retryAccounting(Issue68FakeOpenAiEndpoint.Mode.RATE_LIMIT, 429);
    }

    @Test
    void 하위_503_followup도_최종응답과_예약을_합치지_않는다() throws Exception {
        retryAccounting(Issue68FakeOpenAiEndpoint.Mode.RETRY_503, 503);
    }

    private void retryAccounting(Issue68FakeOpenAiEndpoint.Mode first, int status) throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("retry-" + status + ".jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.gzip(true);
            endpoint.enqueue(first, Issue68FakeOpenAiEndpoint.Mode.OK);
            // when
            nativeRequest(endpoint, boundary, response -> assertThat(response.promptTokens()).isEqualTo(900L));
            // then
            assertThat(boundary.calls()).hasSize(1);
            assertThat(endpoint.received()).isEqualTo(2);
            assertThat(boundary.attempts()).extracting(Issue68HttpBoundary.Attempt::status).containsExactly(status, 200);
            assertThat(boundary.attempts()).extracting(Issue68HttpBoundary.Attempt::settlement)
                    .containsExactly("UNKNOWN_KEEP", "SETTLED");
            assertThat(boundary.attempts()).allMatch(a -> a.contentEncoding().equals("gzip"));
            assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(128000, 128) + Issue68Budget.cost(900, 20));
            assertThat(budget.grossReservedNanos()).isEqualTo(2 * Issue68Budget.cost(128000, 128));
        }
    }

    @Test
    void 압축_Batch의_Usage는_요청당_한번이며_SDK_응답과_일치한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("batch.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.gzip(true);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), true).run(context -> {
                var sdkUsage = new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage>();
                context.getBean(io.micrometer.observation.ObservationRegistry.class).observationConfig().observationHandler(
                        new io.micrometer.observation.ObservationHandler<org.springframework.ai.chat.observation.ChatModelObservationContext>() {
                            @Override
                            public boolean supportsContext(io.micrometer.observation.Observation.Context candidate) {
                                return candidate instanceof org.springframework.ai.chat.observation.ChatModelObservationContext;
                            }

                            @Override
                            public void onStop(org.springframework.ai.chat.observation.ChatModelObservationContext observation) {
                                if (observation.getResponse() != null) {
                                    sdkUsage.set(observation.getResponse().getMetadata().getUsage());
                                }
                            }
                        });
                var cases = Issue68LiveSession.smokeCases();
                var call = boundary.begin(cases.stream().map(Issue68EvaluationContract.Case::id).toList(), true);
                var items = java.util.stream.IntStream.range(0, cases.size()).mapToObj(i ->
                        new AiModerationBatchPort.Item("m%03d".formatted(i + 1), cases.get(i).content())).toList();
                // when
                var result = context.getBean(AiModerationBatchPort.class).analyze(items);
                boundary.end(call, false);
                // then
                assertThat(result.results()).hasSize(2);
                assertThat(boundary.attempts()).hasSize(1);
                var usage = boundary.attempts().get(0).usage();
                assertThat(usage).isEqualTo(new Issue68Budget.Usage(900, 40, 940, 0L));
                assertThat(sdkUsage.get()).isNotNull();
                assertThat(sdkUsage.get().getPromptTokens().longValue()).isEqualTo(usage.input());
                assertThat(sdkUsage.get().getCompletionTokens().longValue()).isEqualTo(usage.output());
                assertThat(sdkUsage.get().getTotalTokens().longValue()).isEqualTo(usage.total());
                assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(900, 40));
            });
        }
    }

    @Test
    void Usage_누락과_JSON_손상_응답유실은_모든_예약을_유지한다() throws Exception {
        // given
        for (var mode : List.of(Issue68FakeOpenAiEndpoint.Mode.NO_USAGE, Issue68FakeOpenAiEndpoint.Mode.BROKEN_JSON,
                Issue68FakeOpenAiEndpoint.Mode.DROP_RESPONSE)) {
            try (var endpoint = new Issue68FakeOpenAiEndpoint();
                    var budget = new Issue68Budget(temporary.resolve(mode.name() + ".jsonl"), Issue68Budget.PLANNED_LIMIT);
                    var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
                endpoint.defaultMode(mode);
                // when
                if (mode == Issue68FakeOpenAiEndpoint.Mode.NO_USAGE) {
                    nativeRequest(endpoint, boundary, response -> assertThat(response.promptTokens()).isZero());
                } else {
                    assertThatThrownBy(() -> nativeRequest(endpoint, boundary, response -> { }))
                            .isInstanceOf(RuntimeException.class);
                }
                // then
                assertThat(boundary.attempts()).isNotEmpty().allMatch(a -> a.usage() == null);
                assertThat(budget.chargedNanos()).isEqualTo(boundary.attempts().size() * Issue68Budget.cost(128000, 128));
            }
        }
    }

    @Test
    void 응답후_journal_실패는_이중정산없이_다음전송을_차단한다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("settle-failed.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.beforeResponse(() -> {
                try {
                    var field = Issue68Budget.class.getDeclaredField("journal");
                    field.setAccessible(true);
                    ((java.nio.channels.FileChannel) field.get(budget)).close();
                } catch (Exception failure) {
                    throw new IllegalStateException("FIXTURE_CHANNEL_CLOSE_FAILED");
                }
            });
            // when & then
            assertThatThrownBy(() -> nativeRequest(endpoint, boundary, response -> { })).isInstanceOf(RuntimeException.class);
            assertThat(endpoint.received()).isEqualTo(1);
            assertThat(budget.blocked()).isTrue();
            assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(128000, 128));
            assertThat(boundary.attempts()).hasSize(1);
            assertThat(boundary.attempts().get(0).settlement()).isEqualTo("SETTLEMENT_FAILED");
            assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false)).hasMessage("ISSUE68_EXECUTION_STOPPED");
        }
    }

    @Test
    void 재시작후_과거_미확정예약과_누적예약_상한은_새_Usage로_초기화되지_않는다() throws Exception {
        // given: 실제 journal은 건드리지 않고 같은 금액의 fixture를 만든다.
        var path = temporary.resolve("restored.jsonl");
        Issue68Budget.Ticket old;
        try (var first = Issue68Budget.restoreSmoke(path)) {
            old = first.reserve(128);
            first.settle(old, null);
        }
        // when
        try (var restored = Issue68Budget.restoreSmoke(path)) {
            var next = restored.reserve(128);
            restored.settle(next, new Issue68Budget.Usage(900, 20, 920, 0L));
            // then
            assertThat(restored.chargedNanos()).isEqualTo(19_276_800L + Issue68Budget.cost(900, 20));
            assertThat(restored.grossReservedNanos()).isEqualTo(2 * 19_276_800L);
            assertThatThrownBy(() -> restored.settle(old, new Issue68Budget.Usage(824, 13, 837, null)))
                    .hasMessage("ISSUE68_UNKNOWN_TICKET");
        }
    }

    private static void nativeRequest(Issue68FakeOpenAiEndpoint endpoint, Issue68HttpBoundary boundary,
            java.util.function.Consumer<com.bobfull.chat.application.result.AiModerationResult> assertion) {
        Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
            var c = Issue68LiveSession.smokeCases().get(0);
            var call = boundary.begin(List.of(c.id()), false);
            boolean failed = true;
            try {
                var result = context.getBean(AiModerationPort.class).analyze(c.content());
                failed = false;
                assertion.accept(result);
            } finally {
                boundary.end(call, failed);
            }
        });
    }
}
