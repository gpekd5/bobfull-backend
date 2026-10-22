package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class Issue68LiveResumeTest {
    @TempDir
    Path temporary;

    @Test
    void 명시적_재개는_기존_claim과_예약을_보존하고_새_manifest를_분리한다() throws Exception {
        // given
        Path root = fixture();
        String original = Files.readString(root.resolve("budget.jsonl"));
        String claim = Files.readString(root.resolve("approval.claim"));
        // when
        try (var session = new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL)) {
            session.checkpoint("final", "FREE_FIXTURE_ONLY");
            // then
            assertThat(session.budget().chargedNanos()).isEqualTo(19_276_800L);
            assertThat(session.budget().grossReservedNanos()).isEqualTo(19_276_800L);
            assertThat(Files.readString(root.resolve("budget.jsonl"))).isEqualTo(original);
            assertThat(Files.readString(root.resolve("approval.claim"))).isEqualTo(claim);
            assertThat(session.directory()).isNotEqualTo(root);
            var manifest = new JsonMapper().readTree(Files.readString(session.directory().resolve("approval-manifest.json")));
            assertThat(manifest.path("firstNewAttempt").asInt()).isEqualTo(2);
            assertThat(manifest.path("maxNewHttpAttempts").asInt()).isEqualTo(4);
            assertThat(manifest.path("automaticRestartAllowed").asBoolean()).isFalse();
            assertThat(Files.exists(root.resolve("checkpoint-final.json"))).isFalse();
        }
    }

    @Test
    void 재개_승인은_중복_실행과_중단후_자동재개를_허용하지_않는다() throws Exception {
        // given
        Path root = fixture();
        // when & then
        try (var session = new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL)) {
            assertThatThrownBy(() -> new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL))
                    .isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(() -> Issue68Budget.restoreSmoke(root.resolve("budget.jsonl")))
                    .isInstanceOf(java.io.IOException.class);
        }
        assertThatThrownBy(() -> new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL))
                .hasMessage("ISSUE68_RESUME_REAPPROVAL_REQUIRED");
    }

    @Test
    void journal이_없으면_새_장부로_예산을_초기화하지_않는다() throws Exception {
        // given
        Path root = temporary.resolve("missing");
        Files.createDirectory(root);
        Files.writeString(root.resolve("approval.claim"), claim());
        // when & then
        assertThatThrownBy(() -> new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(root.resolve("budget.jsonl"))).isFalse();
    }

    @Test
    void 과거장부_변조와_다른_승인은_재개_전송없이_차단한다() throws Exception {
        // given
        Path root = fixture();
        // when & then
        assertThatThrownBy(() -> new Issue68LiveSession(root, "not-approved"))
                .hasMessage("ISSUE68_RESUME_STATE_INVALID");
        Files.writeString(root.resolve("budget.jsonl"), "corrupt\n");
        assertThatThrownBy(() -> new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(root.resolve(Issue68LiveSession.RESUME_APPROVAL))).isFalse();
    }

    @Test
    void 재개후_정산은_과거_미확정예약을_해제하지_않으며_장부에_추가만_한다() throws Exception {
        // given
        Path root = fixture();
        String prefix = Files.readString(root.resolve("budget.jsonl"));
        // when
        try (var session = new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL)) {
            var ticket = session.budget().reserve(128);
            var usage = new Issue68Budget.Usage(824, 13, 837, 0L);
            session.budget().settle(ticket, usage);
            // then
            assertThat(ticket.number()).isEqualTo(2);
            assertThat(session.budget().chargedNanos()).isEqualTo(19_276_800L + 131_400L);
            assertThatThrownBy(() -> session.budget().settle(ticket, usage)).hasMessage("ISSUE68_UNKNOWN_TICKET");
            assertThat(Files.readString(root.resolve("budget.jsonl"))).startsWith(prefix);
        }
        try (var restored = Issue68Budget.restoreSmoke(root.resolve("budget.jsonl"))) {
            assertThat(restored.chargedNanos()).isEqualTo(19_408_200L);
            assertThat(restored.grossReservedNanos()).isEqualTo(38_553_600L);
        }
    }

    @Test
    void SDK_Retry도_새_HTTP_4회_상한에_포함되어_다섯번째는_서버에_도달하지_않는다() throws Exception {
        // given: 실제 Live와 같은 ceiling을 loopback transport에서 검증한다.
        Path root = fixture();
        try (var session = new Issue68LiveSession(root, Issue68LiveSession.RESUME_APPROVAL);
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = new Issue68HttpBoundary(endpoint.uri(), session.budget(), false, Issue68LiveSession.RESUME_HTTP_LIMIT)) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.SERVER_ERROR);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var c = Issue68LiveSession.smokeCases().get(0);
                // when: SDK 기본 maxRetries=3은 변경하지 않는다.
                for (int i = 0; i < 2; i++) {
                    var call = boundary.begin(List.of(c.id()), false);
                    assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content()))
                            .isInstanceOf(RuntimeException.class);
                    boundary.end(call, true);
                }
                // then
                assertThat(endpoint.received()).isEqualTo(4);
                assertThat(boundary.attempts()).hasSize(4).allMatch(a -> a.settlement().equals("UNKNOWN_KEEP"));
                assertThat(session.budget().attempts()).isEqualTo(5); // 과거1 + 신규4
                assertThat(session.budget().grossReservedNanos()).isEqualTo(96_384_000L).isLessThan(100_000_000L);
                assertThatThrownBy(() -> boundary.begin(List.of(c.id()), false)).hasMessage("ISSUE68_EXECUTION_STOPPED");
            });
        }
    }

    @Test
    void 신규_HTTP_4회_최악예약과_과거예약의_합이_십센트_미만이다() {
        // given & when
        long worst = 19_276_800L + 4 * Issue68Budget.cost(128000, 256);
        // then
        assertThat(worst).isEqualTo(96_691_200L).isLessThan(100_000_000L);
    }

    @Test
    void 새_SDK_대조기는_압축_Native와_Batch_Usage를_각_요청에서_일치시킨다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("parity.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            endpoint.gzip(true);
            var sdk = new Issue68SdkUsage(boundary);
            var cases = Issue68LiveSession.smokeCases();
            // when
            for (boolean batch : List.of(false, true)) {
                Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), batch).run(context -> {
                    context.getBean(io.micrometer.observation.ObservationRegistry.class)
                            .observationConfig().observationHandler(sdk);
                    var selected = batch ? cases : cases.subList(0, 1);
                    var call = boundary.begin(selected.stream().map(Issue68EvaluationContract.Case::id).toList(), batch);
                    if (batch) {
                        context.getBean(com.bobfull.chat.application.port.AiModerationBatchPort.class).analyze(
                                java.util.stream.IntStream.range(0, selected.size()).mapToObj(i ->
                                        new com.bobfull.chat.application.port.AiModerationBatchPort.Item(
                                                "m%03d".formatted(i + 1), selected.get(i).content())).toList());
                    } else {
                        context.getBean(AiModerationPort.class).analyze(selected.get(0).content());
                    }
                    boundary.end(call, false);
                    // then
                    assertThat(sdk.matches(call.number)).isTrue();
                });
            }
            assertThat(sdk.entries()).hasSize(2);
            assertThat(sdk.matches(3)).isFalse();
            assertThat(budget.attempts()).isEqualTo(2);
        }
    }

    private Path fixture() throws Exception {
        Path root = temporary.resolve("live-fixture");
        Files.createDirectory(root);
        Files.writeString(root.resolve("approval.claim"), claim());
        // 역사적 두 줄의 비밀정보 없는 fixture다. 실제 journal은 읽거나 수정하지 않는다.
        Files.writeString(root.resolve("budget.jsonl"), """
                {"sha256":"38fae47c0190018582baf2dc98d1cbcd3e47fd51dd6be3e5599be7caff1e31e7","payload":"{\\"event\\":\\"RESERVE\\",\\"attempt\\":1,\\"nanoUsd\\":19276800}"}
                {"sha256":"8490c63a69727bc50c11f6d6694b959e66e4f7390d3852bc376a8fd7ceb13ce2","payload":"{\\"event\\":\\"UNKNOWN_KEEP\\",\\"attempt\\":1,\\"nanoUsd\\":19276800}"}
                """);
        return root;
    }

    private static String claim() {
        return Issue68LiveSession.APPROVAL + "\n" + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n";
    }
}
