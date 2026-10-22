package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Issue68ExpandedHttpSafetyTest {
    @TempDir Path temporary;

    private Path fixture() throws Exception {
        Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        return temporary;
    }

    @Test
    void 준비용_확장경로는_HTTPS와_비loopback을_실제전송없이_차단한다() throws Exception {
        // given
        try (var session = new Issue68ExpandedPreparationSession(fixture(), Issue68ExpandedPlan.Stage.PILOT)) {
            session.plan().start(Issue68ExpandedPlan.matrix().get(0));
            // when & then: API Key와 TLS socket은 사용하지 않는다.
            assertThatThrownBy(() -> session.boundary(java.net.URI.create("https://api.openai.com/v1")))
                    .hasMessage("ISSUE68_LIVE_ENDPOINT_NOT_AUTHORIZED");
            assertThat(session.plan().stopped()).isTrue();
            assertThat(session.budget().stageAttempts()).isZero();
        }
    }

    @Test
    void poll_lease가_부족하면_예산예약과_HTTP보다_먼저_차단한다() throws Exception {
        // given
        try (var session = new Issue68ExpandedPreparationSession(fixture(), Issue68ExpandedPlan.Stage.PILOT);
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.boundary(endpoint.uri())) {
            session.plan().start(Issue68ExpandedPlan.matrix().get(0));
            boundary.beforeExchange(() -> { throw new IllegalStateException("fixture-stale-poll"); });
            var c = Issue68EvaluationContract.diagnostic().get(0);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var call = boundary.begin(List.of(c.id()), false);
                // when
                assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content())).isInstanceOf(RuntimeException.class);
                boundary.end(call, true);
                // then
                assertThat(endpoint.received()).isZero();
                assertThat(session.budget().stageAttempts()).isZero();
                assertThat(session.plan().stopped()).isTrue();
            });
        }
    }

    @Test
    void SDK와_HTTP의_Usage불일치는_신규환급만_복구하고_과거UNKNOWN은_유지한다() throws Exception {
        // given
        Path path = fixture();
        try (var session = new Issue68ExpandedPreparationSession(path, Issue68ExpandedPlan.Stage.PILOT);
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.boundary(endpoint.uri())) {
            session.plan().start(Issue68ExpandedPlan.matrix().get(0));
            var c = Issue68EvaluationContract.diagnostic().get(0);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var call = boundary.begin(List.of(c.id()), false);
                context.getBean(AiModerationPort.class).analyze(c.content());
                // when: SDK 판독 오류만 주입한다.
                boundary.sdkUsage(new Issue68SdkUsage.Entry(call.number, "gpt-4o-mini-2024-07-18",
                        new Issue68Budget.Usage(900, 21, 921, 0L)));
                boundary.end(call, false);
                // then
                assertThat(session.budget().chargedNanos()).isEqualTo(19_587_900L + 19_276_800L);
                assertThat(session.budget().grossReservedNanos()).isEqualTo(57_907_200L + 19_276_800L);
                assertThat(boundary.attempts()).extracting(Issue68HttpBoundary.Attempt::settlement).containsExactly("DISPUTED_KEEP");
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false))
                        .hasMessage("ISSUE68_EXPANDED_HOLD");
                assertThat(endpoint.received()).isEqualTo(1);
            });
        }
        try (var audit = Issue68Budget.auditExpandedPreparation(path.resolve("budget.jsonl"))) {
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + 19_276_800L);
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
    }

    @Test
    void HTTP상한은_단계예산이_남아도_초과할수_없다() throws Exception {
        // given
        try (var session = new Issue68ExpandedPreparationSession(fixture(), Issue68ExpandedPlan.Stage.PILOT)) {
            // when: 전송 없이 회계 counter 경계만 검증한다.
            for (int i = 0; i < 480; i++) session.budget().settle(session.budget().reserve(128),
                    new Issue68Budget.Usage(0, 0, 0, 0L));
            // then
            assertThat(session.budget().stageChargedNanos()).isZero();
            assertThatThrownBy(() -> session.budget().reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(session.budget().stageAttempts()).isEqualTo(480);
        }
    }
}
