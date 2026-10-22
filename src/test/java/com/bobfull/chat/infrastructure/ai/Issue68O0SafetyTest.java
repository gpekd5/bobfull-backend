package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class Issue68O0SafetyTest {
    @TempDir
    Path temporary;

    private Path fixture(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name)).toRealPath();
        Issue68O0PreparationTest.priorFixture(root.resolve("budget.jsonl"));
        Files.writeString(root.resolve("approval.claim"), Issue68LiveSession.APPROVAL + "\n"
                + Issue68EvaluationContract.FROZEN_GT_SHA256 + "\n");
        return root;
    }

    @Test
    void 승인없이_O0를_열면_Key나_실제_장부에_접근하지_않고_거절한다() {
        // given & when & then: 테스트 프로세스에 유료 승인변수를 주입하지 않는다.
        assertThatThrownBy(Issue68O0Session::open).hasMessage("ISSUE68_O0_PAID_APPROVAL_REQUIRED");
    }

    @Test
    void 동결_manifest는_정책와_Metadata와_Source와_누적_부담을_고정한다() throws Exception {
        // given
        var manifest = Issue68O0Session.manifest();
        String hash = Issue68O0Session.manifestSha256();
        // when & then
        assertThat(hash).isEqualTo(Issue68O0Session.manifestSha256());
        assertThat(manifest).containsEntry("priorUnknownNanoUsd", 19_276_800L)
                .containsEntry("priorGrossNanoUsd", 57_907_200L).containsEntry("automaticRestartAllowed", false);
        assertThat(manifest.get("contract")).isEqualTo(new java.util.TreeMap<>(Issue68O0Plan.frozenContract()));
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/o0-approval-manifest-proposal.json"), new JsonMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("sha256", hash, "manifest", manifest)));
    }

    @Test
    void 다른_manifest와_과거_승인_부재는_장부를_추가하지_않고_차단한다() throws Exception {
        // given
        Path root = fixture("invalid");
        String before = Files.readString(root.resolve("budget.jsonl"));
        // when & then
        assertThatThrownBy(() -> new Issue68O0Session(root, "0".repeat(64)))
                .hasMessage("ISSUE68_O0_APPROVAL_OR_PATH_CHANGED");
        Files.delete(root.resolve("approval.claim"));
        assertThatThrownBy(() -> new Issue68O0Session(root, Issue68O0Session.manifestSha256()))
                .hasMessage("ISSUE68_O0_PRIOR_APPROVAL_REQUIRED");
        assertThat(Files.readString(root.resolve("budget.jsonl"))).isEqualTo(before);
    }

    @Test
    void 동일_장부_잠금과_재시작_차단은_Live_승인_경계에서도_유지된다() throws Exception {
        // given
        Path root = fixture("restart");
        String before = Files.readString(root.resolve("budget.jsonl"));
        String approval = Files.readString(root.resolve("approval.claim"));
        // when
        try (var session = new Issue68O0Session(root, Issue68O0Session.manifestSha256())) {
            session.budget().reserve(1280); // crash 직전 미확정 예약을 그대로 모사한다.
            assertThatThrownBy(() -> Issue68Budget.auditO0(root.resolve("budget.jsonl"))).isInstanceOf(java.io.IOException.class);
            session.checkpoint("test", Map.of("state", "pending"));
            session.checkpoint("test", Map.of("state", "pending-again"));
            try (var files = Files.list(root.resolve(Issue68O0Session.APPROVAL))) {
                assertThat(files.count()).isEqualTo(3); // manifest + overwrite 없는2 checkpoint
            }
        }
        // then
        assertThat(Files.readString(root.resolve("budget.jsonl"))).startsWith(before);
        assertThat(Files.readString(root.resolve("approval.claim"))).isEqualTo(approval);
        try (var audit = Issue68Budget.auditO0(root.resolve("budget.jsonl"))) {
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + Issue68Budget.cost(128_000, 1280));
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        assertThatThrownBy(() -> new Issue68O0Session(root, Issue68O0Session.manifestSha256()))
                .hasMessage("ISSUE68_O0_REAPPROVAL_REQUIRED");
    }

    @Test
    void Native_일반FP를_점수에_남기면서_동일_FP_비교는_진행하고_새FP는_차단한다() {
        // given
        var safe = find("HOLDOUT-SAFE-10");
        var fp = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.SPAM), RiskLevel.MEDIUM);
        // when & then
        assertThat(Issue68GateV2.executionBlockers(safe, fp, true, true, null)).isEmpty();
        assertThat(Issue68GateV2.evaluate(safe, fp, true).exactAndReview().resultExact()).isFalse();
        assertThat(Issue68GateV2.executionBlockers(safe, fp, true, false, fp)).isEmpty();
        assertThat(Issue68GateV2.executionBlockers(safe, fp, true, false, safe.expected())).contains("NEW_FP");
        assertThat(Issue68GateV2.paired(safe, fp, fp).commonErrors()).contains("COMMON_FP");
        assertThat(Issue68GateV2.executionBlockers(safe,
                new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.PROFANITY), RiskLevel.MEDIUM),
                true, false, fp)).contains("COMMON_FP_CHANGED_REQUIRES_REVIEW");
    }

    @Test
    void Native_FP로_Poll이_거절되지_않고_Critical과_검토누락은_즉시_다음요청을_막는다() {
        // given
        var plan = new Issue68O0Plan();
        var fp = new ModerationResult(ModerationResultType.FLAGGED, Set.of(ModerationCategory.SPAM), RiskLevel.MEDIUM);
        var results = Issue68O0Plan.cases().stream().map(c -> {
            var actual = c.id().equals("HOLDOUT-SAFE-10") ? fp : c.expected();
            plan.committed(c, actual, true);
            return Issue68GateV2.evaluate(c, actual, true);
        }).toList();
        Issue68O0Plan.cases().stream().filter(c -> new com.bobfull.chat.application.service.ModerationRulePolicy()
                .clearFlagged(c.content()).isEmpty()).forEach(c -> plan.begin(List.of(c.id()), false));
        // when
        plan.beginPoll(results, true);
        plan.committed(find("HOLDOUT-SAFE-10"), fp, true);
        assertThat(plan.stopped()).isFalse();
        plan.committed(find("HOLDOUT-PROFANITY-16"),
                new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW), true);
        // then
        assertThat(plan.holds().get("HOLDOUT-PROFANITY-16")).contains("FN", "REVIEW_TARGET_MISS");
        assertThatThrownBy(() -> plan.begin(List.of("HOLDOUT-SPAM-08"), false)).hasMessage("ISSUE68_O0_LOGICAL_CONTRACT");
        var critical = find("HOLDOUT-PROFANITY-14");
        assertThat(Issue68GateV2.executionBlockers(critical,
                new ModerationResult(ModerationResultType.FLAGGED, critical.expected().categories(), RiskLevel.MEDIUM),
                true, true, null)).contains("CRITICAL_RISK_DOWNGRADE");
    }

    @Test
    void HTTPS_O0도_실제_Payload_모델_스키마_cap_목적지_변조를_전송전에_차단한다() throws Exception {
        // given: verifyRequest만 호출한다. HTTPS socket/API key는 사용하지 않는다.
        try (var session = new Issue68O0Session(fixture("https"), Issue68O0Session.manifestSha256());
                var boundary = session.boundary()) {
            var c = find("HOLDOUT-SPAM-12"); // 과거 Smoke whitelist 밖인 승인80건
            var logical = boundary.begin(List.of(c.id()), false);
            var json = new JsonMapper();
            var body = json.createObjectNode().put("model", "gpt-4o-mini").put("max_tokens", 128);
            body.set("messages", json.valueToTree(List.of(Map.of("role", "system", "content", ModerationPrompt.SYSTEM_PROMPT),
                    Map.of("role", "user", "content", c.content()))));
            body.set("response_format", json.valueToTree(Map.of("type", "json_schema", "json_schema",
                    Map.of("schema", json.readTree(Issue68EvaluationContract.schema(false))))));
            boundary.verifyRequest(request("https://api.openai.com/v1/chat/completions", body.toString()));
            for (String url : List.of("http://api.openai.com/v1/chat/completions", "https://evil.example/v1/chat/completions",
                    "https://api.openai.com/v1/chat/completions?redirect=1")) {
                assertThatThrownBy(() -> boundary.verifyRequest(request(url, body.toString())))
                        .hasMessage("ISSUE68_HTTP_DESTINATION_BLOCKED");
            }
            for (String field : List.of("model", "max_tokens", "tools", "temperature", "response_format")) {
                var changed = body.deepCopy();
                changed.put(field, "changed");
                assertThatThrownBy(() -> boundary.verifyRequest(request("https://api.openai.com/v1/chat/completions",
                        changed.toString()))).isInstanceOf(IllegalStateException.class);
            }
            boundary.end(logical, false);
            assertThat(boundary.attempts()).isEmpty();
            assertThat(session.budget().stageAttempts()).isZero();
        }
    }

    @Test
    void 실제_SDK_Retry도_Live와_같은_장부에_HTTP별_예약과_UNKNOWN을_남긴다() throws Exception {
        // given
        try (var session = new Issue68O0Session(fixture("retry"), Issue68O0Session.manifestSha256());
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = session.rehearsalBoundary(endpoint.uri())) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.RATE_LIMIT, Issue68FakeOpenAiEndpoint.Mode.OK);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var logical = boundary.begin(List.of("HOLDOUT-SAFE-01"), false);
                var sdk = new Issue68SdkUsage(boundary);
                context.getBean(io.micrometer.observation.ObservationRegistry.class).observationConfig().observationHandler(sdk);
                // when
                context.getBean(AiModerationPort.class).analyze(find("HOLDOUT-SAFE-01").content());
                boundary.end(logical, false);
                // then
                assertThat(endpoint.received()).isEqualTo(2);
                assertThat(boundary.attempts()).extracting(Issue68HttpBoundary.Attempt::settlement)
                        .containsExactly("UNKNOWN_KEEP", "SETTLED");
                assertThat(session.budget().stageAttempts()).isEqualTo(2);
                assertThat(session.budget().stageChargedNanos()).isEqualTo(
                        Issue68Budget.cost(128_000, 128) + Issue68Budget.cost(900, 20));
                assertThat(sdk.matches(logical.number)).isTrue();
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-02"), false))
                        .hasMessage("ISSUE68_O0_LOGICAL_CONTRACT");
            });
        }
    }

    @Test
    void 예약잔액_부족이면_SDK가_재시도해도_Fake_endpoint에_HTTP가_도달하지_않는다() throws Exception {
        // given
        try (var session = new Issue68O0Session(fixture("blocked"), Issue68O0Session.manifestSha256());
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = session.rehearsalBoundary(endpoint.uri())) {
            for (int i = 0; i < 5; i++) {
                session.budget().settle(session.budget().reserve(1280), null);
            }
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var logical = boundary.begin(List.of("HOLDOUT-SAFE-01"), false);
                // when & then
                assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(find("HOLDOUT-SAFE-01").content()))
                        .isInstanceOf(RuntimeException.class);
                boundary.end(logical, true);
                assertThat(endpoint.received()).isZero();
                assertThat(session.budget().stageAttempts()).isEqualTo(5);
                assertThat(session.budget().stageChargedNanos()).isEqualTo(99_840_000L);
            });
        }
    }

    private static okhttp3.Request request(String url, String body) {
        return new okhttp3.Request.Builder().url(url)
                .post(okhttp3.RequestBody.create(body, okhttp3.MediaType.get("application/json"))).build();
    }

    @Test
    void HTTP와_SDK_Usage가_충돌하면_환급액을_다시_예약부담으로_유지하고_재시작도_차단한다() throws Exception {
        // given
        Path root = fixture("disputed");
        try (var session = new Issue68O0Session(root, Issue68O0Session.manifestSha256());
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = session.rehearsalBoundary(endpoint.uri())) {
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var call = boundary.begin(List.of("HOLDOUT-SAFE-01"), false);
                context.getBean(AiModerationPort.class).analyze(find("HOLDOUT-SAFE-01").content());
                // when: SDK 판독 결함을 주입한다. Provider나 GT를 바꾸지 않는다.
                boundary.sdkUsage(new Issue68SdkUsage.Entry(call.number, "gpt-4o-mini-2024-07-18",
                        new Issue68Budget.Usage(900, 21, 921, 0L)));
                boundary.end(call, false);
                // then
                assertThat(boundary.attempts()).extracting(Issue68HttpBoundary.Attempt::settlement).containsExactly("DISPUTED_KEEP");
                assertThat(session.budget().stageChargedNanos()).isEqualTo(Issue68Budget.cost(128_000, 128));
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-02"), false))
                        .hasMessage("ISSUE68_O0_LOGICAL_CONTRACT");
                assertThat(endpoint.received()).isEqualTo(1);
            });
        }
        try (var audit = Issue68Budget.auditO0(root.resolve("budget.jsonl"))) {
            assertThat(audit.stageChargedNanos()).isEqualTo(Issue68Budget.cost(128_000, 128));
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
    }

    private static Issue68EvaluationContract.Case find(String id) {
        return Issue68EvaluationContract.heldout().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void Usage_충돌_보완으로도_과거_정산이나_UNKNOWN을_소급_변경할수_없다() throws Exception {
        // given
        Path root = fixture("prior-immutable");
        try (var session = new Issue68O0Session(root, Issue68O0Session.manifestSha256())) {
            String before = Files.readString(root.resolve("budget.jsonl"));
            // when & then
            assertThatThrownBy(() -> session.budget().retainDisputed(2)).hasMessage("ISSUE68_DISPUTED_USAGE_HOLD");
            assertThat(session.budget().chargedNanos()).isEqualTo(19_587_900L);
            assertThat(session.budget().grossReservedNanos()).isEqualTo(57_907_200L);
            assertThat(Files.readString(root.resolve("budget.jsonl"))).isEqualTo(before);
        }
    }
}
