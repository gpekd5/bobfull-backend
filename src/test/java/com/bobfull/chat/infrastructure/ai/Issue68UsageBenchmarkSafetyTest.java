package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

public class Issue68UsageBenchmarkSafetyTest {
    @TempDir Path temporary;

    // 공개된 5회 실행의 회계 이벤트만 재현한다. 실제 private journal은 읽거나 수정하지 않는다.
    public static Path priorFixture(Path root) throws Exception {
        Issue68A1N1SafetyTest.priorFixture(root);
        var file = root.resolve("budget.jsonl");
        var json = new JsonMapper();
        String prior = Files.readString(file);
        String digest = json.readTree(prior.lines().reduce((a, b) -> b).orElseThrow()).path("sha256").asText();
        var tail = new StringBuilder();
        for (String payload : List.of("{\"event\":\"A1_N1_BEGIN\",\"manifestSha256\":\"3d959747d3334bdf3d8fbc3ea911cdc8f6e5a09305bd30ce94a0cef915bd26cc\",\"nanoUsd\":0,\"attempt\":4,\"priorJournalSha256\":\"80607e4746092d22067d61b818e69bd61f493e197f1a6d4d4cc58789c4fa6547\"}",
                "{\"event\":\"RESERVE\",\"attempt\":5,\"nanoUsd\":19276800}",
                "{\"event\":\"SETTLE\",\"attempt\":5,\"nanoUsd\":158700}")) {
            digest = Issue68EvaluationContract.sha256(digest + "|" + payload);
            var line = new java.util.LinkedHashMap<String, String>();
            line.put("payload", payload); line.put("sha256", digest);
            tail.append(json.writeValueAsString(line)).append('\n');
        }
        assertThat(Issue68EvaluationContract.sha256(prior + tail)).isEqualTo(Issue68UsageBenchmarkPlan.PRIOR_SHA);
        Files.writeString(file, tail, java.nio.file.StandardOpenOption.APPEND);
        return root.toRealPath();
    }

    @Test
    void 승인없이는_Key와_private장부_접근전에_차단한다() {
        assertThatThrownBy(Issue68UsageBenchmarkSession::openBurst).hasMessage("ISSUE68_BENCHMARK_PAID_APPROVAL_REQUIRED");
    }

    @Test
    void Matrix480건과_별도단계예산_원본GT_Production요청을_동결한다() throws Exception {
        var stages = Issue68UsageBenchmarkPlan.Stage.values();
        assertThat(Issue68UsageBenchmarkPlan.units(stages[0])).hasSize(6);
        assertThat(Issue68UsageBenchmarkPlan.units(stages[1])).hasSize(18);
        assertThat(Issue68UsageBenchmarkPlan.cases()).hasSize(20);
        for (var stage : stages) {
            var manifest = Issue68UsageBenchmarkPlan.manifest(stage);
            assertThat(manifest).containsEntry("priorUnknownNanoUsd", 19_276_800L)
                    .containsEntry("requiresSeparateHumanPaidApproval", true).doesNotContainKeys("stageLimits", "orderedInputs");
            assertThat(manifest.get("systemSha256")).isEqualTo(Issue68EvaluationContract.sha256(ModerationPrompt.SYSTEM_PROMPT));
            assertThat(manifest.get("nativeSchemaSha256")).isEqualTo(Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(false)));
            assertThat(manifest.get("batchSchemaSha256")).isEqualTo(Issue68EvaluationContract.sha256(Issue68EvaluationContract.schema(true)));
        }
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/usage-benchmark-burst-approval-proposal.json"), new JsonMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paidExecutionPerformed", false,
                        "manifestSha256", Issue68UsageBenchmarkPlan.manifestSha256(stages[0]),
                        "manifest", Issue68UsageBenchmarkPlan.manifest(stages[0]))));
    }

    @Test
    void Critical_FN_FP_오류는_기록하지만_Benchmark만_계속하며_기술오류는_차단한다() {
        var plan = new Issue68UsageBenchmarkPlan(Issue68UsageBenchmarkPlan.Stage.BURST);
        plan.start(Issue68UsageBenchmarkPlan.units(plan.stage()).get(0));
        var safe = new com.bobfull.chat.application.result.ModerationResult(
                com.bobfull.chat.domain.entity.ModerationResultType.SAFE, java.util.Set.of(), com.bobfull.chat.domain.entity.RiskLevel.LOW);
        var flagged = new com.bobfull.chat.application.result.ModerationResult(
                com.bobfull.chat.domain.entity.ModerationResultType.FLAGGED,
                java.util.Set.of(com.bobfull.chat.domain.entity.ModerationCategory.PROFANITY), com.bobfull.chat.domain.entity.RiskLevel.MEDIUM);
        for (var c : Issue68UsageBenchmarkPlan.cases()) {
            plan.begin(List.of(c.id()), false);
            plan.committed(c, c.expected().result() == com.bobfull.chat.domain.entity.ModerationResultType.SAFE ? flagged : safe, true);
        }
        assertThat(plan.quality()).hasSize(20);
        assertThat(plan.quality().stream().map(row -> (Issue68GateV2.Evaluation) row.get("evaluation")))
                .anyMatch(e -> !e.v1Blockers().isEmpty() && !e.v2Blockers().isEmpty());
        plan.completeUnit(true);
        assertThat(plan.stopped()).isFalse();
        plan.start(Issue68UsageBenchmarkPlan.units(plan.stage()).get(1));
        var c = Issue68UsageBenchmarkPlan.cases().get(0);
        plan.begin(List.of(c.id()), false);
        assertThatThrownBy(() -> plan.committed(c, null, false)).hasMessage("ISSUE68_BENCHMARK_DB_MAPPING");
        assertThatThrownBy(plan::requireActive).hasMessage("ISSUE68_BENCHMARK_TECHNICAL_HOLD");
    }

    @Test
    void 논리당4회와_Burst480회_HTTP상한을_전송전에_검증한다() {
        var plan = new Issue68UsageBenchmarkPlan(Issue68UsageBenchmarkPlan.Stage.BURST);
        for (var unit : Issue68UsageBenchmarkPlan.units(plan.stage())) {
            plan.start(unit);
            int logical = 0;
            for (var c : Issue68UsageBenchmarkPlan.cases()) {
                plan.begin(List.of(c.id()), false);
                for (int i = 0; i < 4; i++) plan.beforeHttp(logical + 1);
                logical++; plan.committed(c, c.expected(), true);
            }
            plan.completeUnit(true);
        }
        assertThat(plan.logicalCount()).isEqualTo(120);
        assertThatThrownBy(() -> plan.beforeHttp(20)).hasMessage("ISSUE68_BENCHMARK_HTTP_LIMIT");
        var extra = new Issue68UsageBenchmarkPlan(Issue68UsageBenchmarkPlan.Stage.BURST);
        extra.start(Issue68UsageBenchmarkPlan.units(extra.stage()).get(0));
        extra.begin(List.of(Issue68UsageBenchmarkPlan.cases().get(0).id()), false);
        for (int i = 0; i < 4; i++) extra.beforeHttp(1);
        assertThatThrownBy(() -> extra.beforeHttp(1)).hasMessage("ISSUE68_BENCHMARK_HTTP_LIMIT");
    }

    @Test
    void 과거_UNKNOWN과_잠금_중복실행_crash예약은_보존되고_자동재개하지않는다() throws Exception {
        Path root = priorFixture(temporary);
        String prior = Files.readString(root.resolve("budget.jsonl"));
        try (var session = Issue68UsageBenchmarkSession.rehearsal(root, Issue68UsageBenchmarkPlan.Stage.BURST)) {
            assertThat(session.budget().chargedNanos()).isEqualTo(19_881_750L);
            assertThat(session.budget().grossReservedNanos()).isEqualTo(96_460_800L);
            session.budget().reserve(1280);
            assertThatThrownBy(() -> Issue68Budget.auditBenchmark(root.resolve("budget.jsonl"))).isInstanceOf(java.io.IOException.class);
        }
        assertThat(Files.readString(root.resolve("budget.jsonl"))).startsWith(prior);
        try (var budget = Issue68Budget.auditBenchmark(root.resolve("budget.jsonl"))) {
            assertThat(budget.chargedNanos()).isEqualTo(19_881_750L + Issue68Budget.cost(128000, 1280));
            assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        assertThatThrownBy(() -> Issue68UsageBenchmarkSession.rehearsal(root, Issue68UsageBenchmarkPlan.Stage.BURST))
                .hasMessage("ISSUE68_BENCHMARK_REAPPROVAL_REQUIRED");
        assertThatThrownBy(() -> Issue68UsageBenchmarkSession.rehearsal(root, Issue68UsageBenchmarkPlan.Stage.REMAINDER))
                .isInstanceOf(java.io.IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "CORRUPT", "SYMLINK", "BAD_MANIFEST"})
    void 장부초기화_손상_링크_변조승인을_차단한다(String fault) throws Exception {
        Path root = priorFixture(temporary);
        Path file = root.resolve("budget.jsonl");
        if (fault.equals("EMPTY")) Files.writeString(file, "");
        if (fault.equals("CORRUPT")) Files.writeString(file, "broken", java.nio.file.StandardOpenOption.APPEND);
        if (fault.equals("SYMLINK")) { Files.move(file, root.resolve("original")); Files.createSymbolicLink(file, root.resolve("original")); }
        assertThatThrownBy(() -> Issue68Budget.startBenchmark(file, Issue68UsageBenchmarkPlan.Stage.BURST,
                fault.equals("BAD_MANIFEST") ? "0".repeat(64) : Issue68UsageBenchmarkPlan.manifestSha256(Issue68UsageBenchmarkPlan.Stage.BURST)))
                .isInstanceOf(java.io.IOException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Issue68FakeOpenAiEndpoint.Mode.class, names = {"NO_USAGE", "RATE_LIMIT", "RETRY_503", "RETRY_KNOWN_USAGE", "BROKEN_JSON", "DROP_RESPONSE"})
    void UNKNOWN은_동일논리Retry까지_후속전송을_차단한다(Issue68FakeOpenAiEndpoint.Mode mode) throws Exception {
        try (var session = Issue68UsageBenchmarkSession.rehearsal(priorFixture(temporary), Issue68UsageBenchmarkPlan.Stage.BURST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            session.plan().start(Issue68UsageBenchmarkPlan.units(session.plan().stage()).get(0));
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                endpoint.enqueue(mode, Issue68FakeOpenAiEndpoint.Mode.OK);
                Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                    var c = Issue68UsageBenchmarkPlan.cases().get(0);
                    var logical = boundary.begin(List.of(c.id()), false);
                    try { context.getBean(AiModerationPort.class).analyze(c.content()); } catch (RuntimeException expected) { }
                    finally { boundary.end(logical, true); }
                });
                assertThat(endpoint.received()).isEqualTo(1);
                assertThat(boundary.attempts()).hasSize(1);
                assertThat(session.budget().stageChargedNanos()).isEqualTo(Issue68Budget.cost(128000, 128));
                assertThatThrownBy(() -> boundary.begin(List.of(Issue68UsageBenchmarkPlan.cases().get(1).id()), false))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void 예산소진과_승인Manifest변조는_실제HTTP_0회에서_차단한다() throws Exception {
        try (var session = Issue68UsageBenchmarkSession.rehearsal(priorFixture(temporary), Issue68UsageBenchmarkPlan.Stage.BURST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            for (int i = 0; i < 5; i++) session.budget().settle(session.budget().reserve(128), new Issue68Budget.Usage(128000, 128, 128128, 0L));
            session.plan().start(Issue68UsageBenchmarkPlan.units(session.plan().stage()).get(0));
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                    var c = Issue68UsageBenchmarkPlan.cases().get(0);
                    boundary.begin(List.of(c.id()), false);
                    assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content())).isInstanceOf(RuntimeException.class);
                });
                assertThat(endpoint.received()).isZero();
            }
        }
    }

    @Test
    void HTTPS는_실전송없이_동일목적지_Production계약과_위변조를_검사한다() throws Exception {
        try (var session = Issue68UsageBenchmarkSession.rehearsal(priorFixture(temporary), Issue68UsageBenchmarkPlan.Stage.BURST)) {
            session.plan().start(Issue68UsageBenchmarkPlan.units(session.plan().stage()).get(0));
            try (var boundary = session.httpsInspectionBoundary()) {
                var c = Issue68UsageBenchmarkPlan.cases().get(0);
                boundary.begin(List.of(c.id()), false);
                var request = request("https://api.openai.com/v1", c);
                boundary.verifyRequest(request);
                assertThatThrownBy(() -> boundary.verifyRequest(request.newBuilder().url("https://example.com/v1/chat/completions").build()))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(session.budget().stageAttempts()).isZero();
            }
        }
    }

    @Test
    void 하위exchange마다_예약하고_다섯번째는_실제전송전에_차단한다() throws Exception {
        try (var session = Issue68UsageBenchmarkSession.rehearsal(priorFixture(temporary), Issue68UsageBenchmarkPlan.Stage.BURST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            session.plan().start(Issue68UsageBenchmarkPlan.units(session.plan().stage()).get(0));
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                var c = Issue68UsageBenchmarkPlan.cases().get(0);
                boundary.begin(List.of(c.id()), false);
                var request = request(endpoint.uri().toString(), c);
                for (int i = 0; i < 4; i++) try (var response = boundary.rehearsalExchange(request)) { assertThat(response.code()).isEqualTo(200); }
                assertThatThrownBy(() -> boundary.rehearsalExchange(request)).isInstanceOf(IllegalStateException.class);
                assertThat(endpoint.received()).isEqualTo(4);
                assertThat(session.budget().stageAttempts()).isEqualTo(4);
                assertThat(session.budget().stageChargedNanos()).isEqualTo(4 * Issue68Budget.cost(900, 20));
            }
        }
    }

    @Test
    void SDK불일치와_중복정산은_예약을_환급하지않고_후속전송을_차단한다() throws Exception {
        try (var session = Issue68UsageBenchmarkSession.rehearsal(priorFixture(temporary), Issue68UsageBenchmarkPlan.Stage.BURST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            session.plan().start(Issue68UsageBenchmarkPlan.units(session.plan().stage()).get(0));
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                var c = Issue68UsageBenchmarkPlan.cases().get(0);
                boundary.begin(List.of(c.id()), false);
                try (var response = boundary.rehearsalExchange(request(endpoint.uri().toString(), c))) { assertThat(response.code()).isEqualTo(200); }
                boundary.sdkUsage(new Issue68SdkUsage.Entry(1, "gpt-4o-mini-2024-07-18", new Issue68Budget.Usage(901, 20, 921, 0L)));
                assertThat(boundary.attempts().get(0).settlement()).isEqualTo("DISPUTED_KEEP");
                assertThat(session.budget().stageChargedNanos()).isEqualTo(Issue68Budget.cost(128000, 128));
                assertThatThrownBy(() -> boundary.begin(List.of(c.id()), false)).isInstanceOf(IllegalStateException.class);
            }
        }
        Path root = priorFixture(Files.createDirectory(temporary.resolve("double")));
        try (var budget = Issue68Budget.startBenchmark(root.resolve("budget.jsonl"), Issue68UsageBenchmarkPlan.Stage.BURST,
                Issue68UsageBenchmarkPlan.manifestSha256(Issue68UsageBenchmarkPlan.Stage.BURST))) {
            var ticket = budget.reserve(128); budget.settle(ticket, new Issue68Budget.Usage(900, 20, 920, 0L));
            long before = budget.chargedNanos();
            assertThatThrownBy(() -> budget.settle(ticket, new Issue68Budget.Usage(900, 20, 920, 0L))).hasMessage("ISSUE68_UNKNOWN_TICKET");
            assertThat(budget.chargedNanos()).isEqualTo(before);
            assertThat(budget.blocked()).isTrue();
        }
    }

    @Test
    void 실행중_승인파일_변조는_후속전송을_차단한다() throws Exception {
        Path root = priorFixture(temporary);
        try (var session = Issue68UsageBenchmarkSession.rehearsal(root, Issue68UsageBenchmarkPlan.Stage.BURST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            session.plan().start(Issue68UsageBenchmarkPlan.units(session.plan().stage()).get(0));
            Files.writeString(root.resolve(session.plan().stage().approval()).resolve("00001-approval-manifest.json"), "tampered fixture");
            assertThatThrownBy(() -> session.rehearsalBoundary(endpoint.uri())).hasMessage("ISSUE68_BENCHMARK_APPROVAL_HOLD");
            assertThat(endpoint.received()).isZero();
        }
    }

    static okhttp3.Request request(String endpoint, Issue68EvaluationContract.Case c) {
        var json = new JsonMapper();
        var schema = Map.of("type", "json_schema", "json_schema", Map.of("name", "ModerationResult", "strict", true,
                "schema", json.readTree(Issue68EvaluationContract.schema(false))));
        return new okhttp3.Request.Builder().url(endpoint + "/chat/completions").post(okhttp3.RequestBody.create(json.writeValueAsString(
                Map.of("model", Issue68EvaluationContract.MODEL, "max_tokens", 128, "response_format", schema, "messages",
                        List.of(Map.of("role", "system", "content", ModerationPrompt.SYSTEM_PROMPT), Map.of("role", "user", "content", c.content())))),
                okhttp3.MediaType.get("application/json"))).build();
    }
}
