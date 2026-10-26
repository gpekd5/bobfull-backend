package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class Issue68ExpandedLiveSafetyTest {
    @TempDir Path temporary;

    private Path fixture() throws Exception {
        Path root = Files.createDirectory(temporary.resolve(java.util.UUID.randomUUID().toString())).toRealPath();
        Issue68O0PreparationTest.priorFixture(root.resolve("budget.jsonl"));
        Files.writeString(root.resolve("approval.claim"), Issue68LiveSession.APPROVAL + "\n"
                + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n");
        return root;
    }

    @Test
    void 별도승인_부재는_Key와_고정장부에_접근하기전에_거절한다() {
        // given & when & then
        assertThatThrownBy(Issue68ExpandedLiveSession::open).hasMessage("ISSUE68_PILOT_PAID_APPROVAL_REQUIRED");
    }

    @Test
    void 승인제안은_Pilot120과_기존부담을_고정하며_과거_O0승인과_구분된다() throws Exception {
        // given
        var manifest = Issue68ExpandedLiveSession.manifest();
        // when & then
        assertThat(manifest).containsEntry("paidExecutionEnabled", false)
                .containsEntry("requiresSeparateHumanPaidApproval", true)
                .containsEntry("globalBurdenNanoUsd", 1_000_000_000L)
                .containsEntry("priorUnknownNanoUsd", 19_276_800L)
                .containsEntry("priorSettledNanoUsd", 311_100L).containsEntry("priorGrossNanoUsd", 57_907_200L);
        assertThat((List<?>) manifest.get("matrix")).hasSize(6);
        assertThat(Issue68ExpandedLiveSession.APPROVAL).isNotEqualTo(Issue68O0Session.APPROVAL);
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/expanded-pilot-approval-proposal.json"), new JsonMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("manifestSha256",
                        Issue68ExpandedLiveSession.manifestSha256(), "manifest", manifest)));
    }

    @Test
    void Live회계도_기존장부의_prefix_UNKNOWN_정산_gross를_보존하고_재시작을_막는다() throws Exception {
        // given
        Path root = fixture();
        Path journal = root.resolve("budget.jsonl");
        String prior = Files.readString(journal);
        // when
        try (var session = Issue68ExpandedLiveSession.rehearsal(root)) {
            assertThat(session.budget().chargedNanos()).isEqualTo(19_587_900L);
            assertThat(session.budget().grossReservedNanos()).isEqualTo(57_907_200L);
            session.budget().settle(session.budget().reserve(128), new Issue68Budget.Usage(824, 13, 837, 0L));
            session.budget().reserve(256); // crash 직전 pending은 환급하지 않는다.
            assertThatThrownBy(() -> Issue68Budget.auditExpandedLiveFixture(journal)).isInstanceOf(java.io.IOException.class);
        }
        // then
        assertThat(Files.readString(journal)).startsWith(prior).contains("EXPANDED_LIVE_BEGIN");
        try (var audit = Issue68Budget.auditExpandedLiveFixture(journal)) {
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + 131_400L + Issue68Budget.cost(128000, 256));
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        assertThatThrownBy(() -> Issue68ExpandedLiveSession.rehearsal(root)).hasMessage("ISSUE68_PILOT_REAPPROVAL_REQUIRED");
    }

    @Test
    void 새장부와_손상장부로_예산을_초기화할수_없다() throws Exception {
        // given
        Path root = fixture();
        Path journal = root.resolve("budget.jsonl");
        Files.writeString(journal, "");
        // when & then
        assertThatThrownBy(() -> Issue68ExpandedLiveSession.rehearsal(root)).hasMessage("ISSUE68_EXPANDED_REAPPROVAL_REQUIRED");
        Files.writeString(journal, "corrupt\n");
        assertThatThrownBy(() -> Issue68ExpandedLiveSession.rehearsal(root)).hasMessage("ISSUE68_JOURNAL_UNAVAILABLE");
    }

    @Test
    void Live회계에서_Pilot잔액과_전역1달러를_각각_검사한다() throws Exception {
        // given
        try (var session = Issue68ExpandedLiveSession.rehearsal(fixture())) {
            // when: 전송 없는 최대 Usage 회계 fixture다.
            for (int i = 0; i < 5; i++) session.budget().settle(session.budget().reserve(1280),
                    new Issue68Budget.Usage(128000, 1280, 129280, 0L));
            // then
            assertThatThrownBy(() -> session.budget().reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(session.budget().stageAttempts()).isEqualTo(5);
        }
        try (var session = Issue68ExpandedLiveSession.rehearsal(fixture())) {
            // 독립 global guard의 결함 주입이며 실제 과거 장부를 수정하지 않는다.
            var charged = Issue68Budget.class.getDeclaredField("charged");
            charged.setAccessible(true);
            charged.setLong(session.budget(), 990_000_000L);
            var baseline = Issue68Budget.class.getDeclaredField("stageBaselineCharge");
            baseline.setAccessible(true);
            baseline.setLong(session.budget(), 990_000_000L);
            assertThatThrownBy(() -> session.budget().reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(session.budget().stageAttempts()).isZero();
        }
    }

    @Test
    void 승인된_HTTPS요청_검증은_socket과_Key없이_검사할수_있다() throws Exception {
        // given
        try (var session = Issue68ExpandedLiveSession.rehearsal(fixture())) {
            session.plan().start(Issue68ExpandedPlan.matrix().get(0));
            try (var boundary = session.httpsInspectionBoundary()) {
                var c = Issue68EvaluationContract.diagnostic().get(0);
                var logical = boundary.begin(List.of(c.id()), false);
                // when: verifyRequest만 호출한다. send/customizer/SDK context는 호출하지 않는다.
                boundary.verifyRequest(request("https://api.openai.com/v1/chat/completions", "valid"));
                boundary.end(logical, false);
                // then
                assertThat(logical.requestSha256).hasSize(64);
                assertThat(session.budget().stageAttempts()).isZero();
                assertThat(boundary.attempts()).isEmpty();
            }
            assertThatThrownBy(session::boundary).hasMessage("ISSUE68_REHEARSAL_HTTPS_BLOCKED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.openai.com/v1/chat/completions", "https://example.org/v1/chat/completions",
            "https://api.openai.com:444/v1/chat/completions", "https://api.openai.com/v1/responses",
            "https://api.openai.com/v1/chat/completions?extra=1"})
    void Live_목적지변경은_예약과_socket전에_차단된다(String url) throws Exception {
        // given
        try (var session = Issue68ExpandedLiveSession.rehearsal(fixture())) {
            session.plan().start(Issue68ExpandedPlan.matrix().get(0));
            try (var boundary = session.httpsInspectionBoundary()) {
                boundary.begin(List.of(Issue68EvaluationContract.diagnostic().get(0).id()), false);
                // when & then
                assertThatThrownBy(() -> boundary.verifyRequest(request(url, "valid")))
                        .hasMessage("ISSUE68_HTTP_DESTINATION_BLOCKED");
                assertThat(session.budget().stageAttempts()).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "cap", "prompt", "schema", "sampling"})
    void Live_Payload변경도_예약과_socket전에_차단된다(String mutation) throws Exception {
        // given
        try (var session = Issue68ExpandedLiveSession.rehearsal(fixture())) {
            session.plan().start(Issue68ExpandedPlan.matrix().get(0));
            try (var boundary = session.httpsInspectionBoundary()) {
                boundary.begin(List.of(Issue68EvaluationContract.diagnostic().get(0).id()), false);
                // when & then
                assertThatThrownBy(() -> boundary.verifyRequest(request("https://api.openai.com/v1/chat/completions", mutation)))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(session.budget().stageAttempts()).isZero();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Issue68FakeOpenAiEndpoint.Mode.class, names = {"NO_USAGE", "RATE_LIMIT", "RETRY_KNOWN_USAGE", "REDIRECT"})
    void Live장부와_같은_경계에서_Usage불명과_Retry는_첫_HTTP후_차단된다(Issue68FakeOpenAiEndpoint.Mode mode) throws Exception {
        // given
        Path root = fixture();
        try (var session = Issue68ExpandedLiveSession.rehearsal(root); var endpoint = new Issue68FakeOpenAiEndpoint()) {
            session.plan().start(Issue68ExpandedPlan.matrix().get(0));
            endpoint.enqueue(mode, Issue68FakeOpenAiEndpoint.Mode.OK);
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                    var c = Issue68EvaluationContract.diagnostic().get(0);
                    var logical = boundary.begin(List.of(c.id()), false);
                    // when: NO_USAGE는 SDK 성공이어도 장부는 HOLD다.
                    try { context.getBean(AiModerationPort.class).analyze(c.content()); }
                    catch (RuntimeException ignored) { /* 오류 원문을 출력하지 않는다. */ }
                    boundary.end(logical, true);
                    // then
                    assertThat(endpoint.received()).isEqualTo(1);
                    assertThat(session.budget().stageAttempts()).isEqualTo(1);
                    assertThat(session.budget().stageChargedNanos()).isEqualTo(19_276_800L);
                    assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-02"), false))
                            .isInstanceOf(IllegalStateException.class);
                });
            }
        }
        try (var audit = Issue68Budget.auditExpandedLiveFixture(root.resolve("budget.jsonl"))) {
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + 19_276_800L);
        }
    }

    private static Request request(String url, String mutation) {
        var json = new JsonMapper();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", mutation.equals("model") ? "not-approved" : Issue68EvaluationContract.MODEL);
        body.put("max_tokens", mutation.equals("cap") ? 256 : 128);
        body.put("messages", List.of(Map.of("role", "system", "content", mutation.equals("prompt")
                        ? "changed" : ModerationPrompt.SYSTEM_PROMPT),
                Map.of("role", "user", "content", Issue68EvaluationContract.diagnostic().get(0).content())));
        body.put("response_format", Map.of("type", "json_schema", "json_schema", Map.of("name", "ModerationResult",
                "strict", true, "schema", mutation.equals("schema") ? Map.of() : json.readTree(Issue68EvaluationContract.schema(false)))));
        if (mutation.equals("sampling")) body.put("temperature", 1);
        return new Request.Builder().url(url).post(RequestBody.create(json.writeValueAsString(body),
                MediaType.get("application/json"))).build();
    }
}
