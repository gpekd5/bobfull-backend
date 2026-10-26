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

public class Issue68A1N1SafetyTest {
    @TempDir Path temporary;

    // 공개된 과거 금액/이벤트 순서만으로 재현한다. private journal 없이 CI에서도 실행 가능하다.
    public static Path priorFixture(Path root) throws Exception {
        Files.createDirectories(root);
        Path path = root.resolve("budget.jsonl");
        Issue68O0PreparationTest.priorFixture(path);
        var json = new JsonMapper();
        String prior = Files.readString(path);
        String digest = json.readTree(prior.lines().reduce((a, b) -> b).orElseThrow()).path("sha256").asText();
        var tail = new StringBuilder();
        // 과거 공개 승인 Manifest 해시다. 현재 승인으로 재사용하지 않는다.
        for (String payload : List.of("{\"globalLimit\":1000000000,\"manifestSha256\":\"3540e688973ad207179e1e549a05ce816ca87ab5750e46aac20e827bae12b5c1\",\"httpLimit\":480,\"nanoUsd\":0,\"stageLimit\":100000000,\"stage\":\"PILOT\",\"attempt\":3,\"event\":\"EXPANDED_LIVE_BEGIN\"}",
                "{\"event\":\"RESERVE\",\"attempt\":4,\"nanoUsd\":19276800}",
                "{\"event\":\"SETTLE\",\"attempt\":4,\"nanoUsd\":135150}")) {
            digest = Issue68EvaluationContract.sha256(digest + "|" + payload);
            var line = new java.util.LinkedHashMap<String, String>();
            line.put("sha256", digest);
            line.put("payload", payload);
            tail.append(json.writeValueAsString(line)).append('\n');
        }
        prior += tail;
        assertThat(Issue68EvaluationContract.sha256(prior)).isEqualTo(Issue68A1N1Plan.PRIOR_SHA);
        Files.writeString(path, tail, java.nio.file.StandardOpenOption.APPEND);
        return root.toRealPath();
    }

    @Test
    void 승인부재는_Key와_기존장부_접근전에_차단한다() {
        // given & when & then
        assertThatThrownBy(Issue68A1N1Session::open).hasMessage("ISSUE68_A1_N1_PAID_APPROVAL_REQUIRED");
    }

    @Test
    void Manifest는_N1순서_GT_A1해시와_예산을_고정하고_유료승인을_대체하지_않는다() throws Exception {
        // given
        var manifest = Issue68A1N1Plan.manifest();
        // when & then
        assertThat(Issue68A1N1Plan.cases()).extracting(Issue68EvaluationContract.Case::id).containsExactlyElementsOf(Issue68A1N1Plan.IDS);
        assertThat(manifest).containsEntry("requiresSeparateHumanPaidApproval", true)
                .containsEntry("promptSha256", Issue68A1N1Plan.PROMPT_SHA).containsEntry("priorUnknownNanoUsd", 19_276_800L);
        assertThat(manifest.get("scope")).isEqualTo("A1_NATIVE_N1_ONLY_NO_N2_BATCH_CONTROL_OR_REPEAT");
        var report = Map.of("paidExecutionPerformed", false, "manifestSha256", Issue68A1N1Plan.manifestSha256(), "manifest", manifest);
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/a1-native-n1-approval-proposal.json"),
                new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @Test
    void 중복실행_crash_재시작에서_예약과_과거_HOLD를_보존한다() throws Exception {
        // given
        Path root = priorFixture(temporary);
        String prior = Files.readString(root.resolve("budget.jsonl"));
        // when
        try (var session = Issue68A1N1Session.rehearsal(root)) {
            assertThat(session.budget().chargedNanos()).isEqualTo(19_723_050L);
            session.budget().reserve(128); // 프로세스 종료를 흉내 낸 pending 예약.
            assertThatThrownBy(() -> Issue68Budget.auditA1N1(root.resolve("budget.jsonl"))).isInstanceOf(java.io.IOException.class);
        }
        // then
        assertThat(Files.readString(root.resolve("budget.jsonl"))).startsWith(prior).contains("A1_N1_BEGIN")
                .doesNotContain("EXPANDED_LIVE_END", "A1_N1_END");
        try (var audit = Issue68Budget.auditA1N1(root.resolve("budget.jsonl"))) {
            assertThat(audit.chargedNanos()).isEqualTo(19_723_050L + 19_276_800L);
            assertThat(audit.grossReservedNanos()).isEqualTo(77_184_000L + 19_276_800L);
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        assertThatThrownBy(() -> Issue68A1N1Session.rehearsal(root)).hasMessage("ISSUE68_A1_N1_REAPPROVAL_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "CORRUPT", "SYMLINK", "BAD_MANIFEST"})
    void 새장부_손상장부_링크_변조승인으로_예산을_초기화할수_없다(String fault) throws Exception {
        // given
        Path root = priorFixture(temporary);
        Path file = root.resolve("budget.jsonl");
        if (fault.equals("EMPTY")) Files.writeString(file, "");
        if (fault.equals("CORRUPT")) Files.writeString(file, "broken", java.nio.file.StandardOpenOption.APPEND);
        if (fault.equals("SYMLINK")) {
            Files.move(file, root.resolve("original.jsonl"));
            Files.createSymbolicLink(file, root.resolve("original.jsonl"));
        }
        // when & then
        assertThatThrownBy(() -> Issue68Budget.startA1N1(file,
                fault.equals("BAD_MANIFEST") ? "0".repeat(64) : Issue68A1N1Plan.manifestSha256())).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void N1만_실행하며_Batch_N2_순서변경_반복을_차단한다() {
        // given
        var plan = new Issue68A1N1Plan();
        // when & then
        assertThatThrownBy(() -> plan.begin(List.of(Issue68A1N1Plan.IDS.get(1)), false)).isInstanceOf(IllegalStateException.class);
        var batch = new Issue68A1N1Plan();
        assertThatThrownBy(() -> batch.begin(Issue68A1N1Plan.IDS.subList(0, 2), true)).isInstanceOf(IllegalStateException.class);
        var valid = new Issue68A1N1Plan();
        for (var c : Issue68A1N1Plan.cases()) {
            valid.begin(List.of(c.id()), false);
            valid.committed(c, c.expected(), true);
        }
        assertThat(valid.complete()).isTrue();
        assertThatThrownBy(() -> valid.begin(List.of(Issue68A1N1Plan.IDS.get(0)), false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 논리당4회와_전체32회_HTTP한도를_별도로_검사한다() {
        // given
        var plan = new Issue68A1N1Plan();
        plan.begin(List.of(Issue68A1N1Plan.IDS.get(0)), false);
        // when & then
        for (int i = 0; i < 4; i++) plan.beforeHttp(1);
        assertThatThrownBy(() -> plan.beforeHttp(1)).hasMessage("ISSUE68_A1_N1_HTTP_LIMIT");
        var full = new Issue68A1N1Plan();
        int logical = 0;
        for (var c : Issue68A1N1Plan.cases()) {
            full.begin(List.of(c.id()), false);
            for (int i = 0; i < 4; i++) full.beforeHttp(logical + 1);
            full.committed(c, c.expected(), true);
            logical++;
        }
        assertThatThrownBy(() -> full.beforeHttp(8)).hasMessage("ISSUE68_A1_N1_HTTP_LIMIT");
    }

    @ParameterizedTest
    @EnumSource(value = Issue68FakeOpenAiEndpoint.Mode.class,
            names = {"NO_USAGE", "RATE_LIMIT", "RETRY_503", "BROKEN_JSON", "DROP_RESPONSE"})
    void UNKNOWN은_같은논리의_SDK와_하위Retry도_즉시차단하고_예약을_유지한다(Issue68FakeOpenAiEndpoint.Mode mode) throws Exception {
        // given
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary));
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.rehearsalBoundary(endpoint.uri())) {
            endpoint.enqueue(mode, Issue68FakeOpenAiEndpoint.Mode.OK);
            // when
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var c = Issue68A1N1Plan.cases().get(0);
                var logical = boundary.begin(List.of(c.id()), false);
                try { context.getBean(AiModerationPort.class).analyze(c.content()); } catch (RuntimeException expected) { }
                finally { boundary.end(logical, true); }
            });
            // then
            assertThat(endpoint.received()).isEqualTo(1);
            assertThat(boundary.attempts()).hasSize(1);
            assertThat(session.budget().chargedNanos()).isEqualTo(19_723_050L + 19_276_800L);
            assertThatThrownBy(() -> boundary.begin(List.of(Issue68A1N1Plan.IDS.get(1)), false)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 오류응답의_Usage필드가_있어도_정산하지않고_SDKRetry를_차단한다() throws Exception {
        // given
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary));
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.rehearsalBoundary(endpoint.uri())) {
            endpoint.defaultMode(Issue68FakeOpenAiEndpoint.Mode.RETRY_KNOWN_USAGE);
            // when
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var c = Issue68A1N1Plan.cases().get(0);
                var logical = boundary.begin(List.of(c.id()), false);
                assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content())).isInstanceOf(RuntimeException.class);
                boundary.end(logical, true);
            });
            // then: non-2xx Usage를 신뢰하지 않는 기존 보수적 판독 계약을 유지한다.
            assertThat(endpoint.received()).isEqualTo(1);
            assertThat(boundary.attempts()).hasSize(1).allSatisfy(a -> assertThat(a.settlement()).isEqualTo("UNKNOWN_KEEP"));
            assertThat(session.budget().stageAttempts()).isEqualTo(1);
        }
    }

    @Test
    void 진단예산이_다음보수예약보다_작으면_HTTP전송0에서_차단한다() throws Exception {
        // given
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary));
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.rehearsalBoundary(endpoint.uri())) {
            for (int i = 0; i < 5; i++) session.budget().settle(session.budget().reserve(128), new Issue68Budget.Usage(128000, 128, 128128, 0L));
            // when
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var c = Issue68A1N1Plan.cases().get(0);
                boundary.begin(List.of(c.id()), false);
                assertThatThrownBy(() -> context.getBean(AiModerationPort.class).analyze(c.content())).isInstanceOf(RuntimeException.class);
            });
            // then
            assertThat(endpoint.received()).isZero();
            assertThat(session.budget().stageChargedNanos()).isEqualTo(96_384_000L);
        }
    }

    @Test
    void 정상8건은_실제Adapter에서_System만_A1으로_바뀌고_요청별Usage를_정산한다() throws Exception {
        // given
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary));
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.rehearsalBoundary(endpoint.uri())) {
            endpoint.gzip(true);
            // when
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                context.getBean(io.micrometer.observation.ObservationRegistry.class).observationConfig().observationHandler(new Issue68SdkUsage(boundary));
                for (var c : Issue68A1N1Plan.cases()) {
                    var logical = boundary.begin(List.of(c.id()), false);
                    var result = context.getBean(AiModerationPort.class).analyze(c.content());
                    boundary.end(logical, false);
                    boundary.committedResult(c, result.result(), true); // DB 검증은 별도 Kafka/MySQL 테스트가 담당한다.
                }
                session.complete();
            });
            // then
            assertThat(endpoint.received()).isEqualTo(8);
            assertThat(boundary.sdkEntries()).hasSize(8);
            assertThat(session.budget().stageChargedNanos()).isEqualTo(8 * Issue68Budget.cost(900, 20));
            for (int i = 0; i < 8; i++) {
                var body = new JsonMapper().readTree(endpoint.requests().get(i));
                assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo(Issue68RiskBoundaryPrompt.CANDIDATE);
                assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo(Issue68A1N1Plan.cases().get(i).content());
                assertThat(body.path("max_tokens").asInt()).isEqualTo(128);
            }
            assertThat(session.budget().blocked()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OK", "HOST", "HTTP", "QUERY", "MODEL", "CAP", "SCHEMA", "CONTENT", "CONTROL", "SAMPLING"})
    void 실제_HTTPS계약은_socket과_Key없이_검사하고_변조를_거절한다(String mutation) throws Exception {
        // given
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary)); var boundary = session.httpsInspectionBoundary()) {
            var json = new JsonMapper();
            var body = json.createObjectNode();
            body.put("model", Issue68EvaluationContract.MODEL);
            body.put("max_tokens", 128);
            body.putArray("messages").addObject().put("role", "system").put("content", Issue68RiskBoundaryPrompt.CANDIDATE);
            ((tools.jackson.databind.node.ArrayNode) body.path("messages")).addObject().put("role", "user")
                    .put("content", Issue68A1N1Plan.cases().get(0).content());
            body.putObject("response_format").put("type", "json_schema").putObject("json_schema")
                    .put("name", "ModerationResult").put("strict", true).set("schema", json.readTree(Issue68EvaluationContract.schema(false)));
            if (mutation.equals("MODEL")) body.put("model", "other-model");
            if (mutation.equals("CAP")) body.put("max_tokens", 129);
            if (mutation.equals("SCHEMA")) ((tools.jackson.databind.node.ObjectNode) body.path("response_format").path("json_schema")).set("schema", json.createObjectNode());
            if (mutation.equals("CONTENT")) ((tools.jackson.databind.node.ObjectNode) body.path("messages").get(1)).put("content", "unapproved-synthetic");
            if (mutation.equals("CONTROL")) ((tools.jackson.databind.node.ObjectNode) body.path("messages").get(0)).put("content", ModerationPrompt.SYSTEM_PROMPT);
            if (mutation.equals("SAMPLING")) body.put("temperature", 0);
            String url = switch (mutation) {
                case "HOST" -> "https://example.invalid/v1/chat/completions";
                case "HTTP" -> "http://api.openai.com/v1/chat/completions";
                case "QUERY" -> "https://api.openai.com/v1/chat/completions?extra=1";
                default -> "https://api.openai.com/v1/chat/completions";
            };
            var request = new okhttp3.Request.Builder().url(url).post(okhttp3.RequestBody.create(json.writeValueAsString(body),
                    okhttp3.MediaType.get("application/json"))).build();
            boundary.begin(List.of(Issue68A1N1Plan.IDS.get(0)), false);
            // when & then: 검사용 객체는 send 자체도 금지하며 실제 TLS/인증정보는 사용하지 않는다.
            if (mutation.equals("OK")) boundary.verifyRequest(request);
            else assertThatThrownBy(() -> boundary.verifyRequest(request)).isInstanceOf(IllegalStateException.class);
            assertThat(session.budget().stageAttempts()).isZero();
            assertThat(boundary.attempts()).isEmpty();
        }
    }

    @Test
    void 하위전송도_매회예약하며_같은논리의_다섯번째_HTTP는_전송하지_않는다() throws Exception {
        // given
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary));
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.rehearsalBoundary(endpoint.uri())) {
            boundary.begin(List.of(Issue68A1N1Plan.IDS.get(0)), false);
            var request = nativeRequest(endpoint.uri(), Issue68A1N1Plan.cases().get(0));
            // when
            for (int i = 0; i < 4; i++) try (var response = boundary.rehearsalExchange(request)) { assertThat(response.code()).isEqualTo(200); }
            // then: 인위적 하위 exchange fixture이며 정상 200을 SDK가 재시도한다는 의미가 아니다.
            assertThatThrownBy(() -> boundary.rehearsalExchange(request)).hasMessage("ISSUE68_A1_N1_HTTP_LIMIT");
            assertThat(endpoint.received()).isEqualTo(4);
            assertThat(session.budget().stageAttempts()).isEqualTo(4);
            assertThat(session.budget().stageGrossReservedNanos()).isEqualTo(4 * 19_276_800L);
        }
    }

    @Test
    void 전체32회_예약이후에는_잔액이_남아도_33번째_예약을_거절한다() throws Exception {
        // given
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary))) {
            // when: 순수 회계 fixture로 HTTP 횟수의 영속 경계만 검사한다.
            for (int i = 0; i < 32; i++) session.budget().settle(session.budget().reserve(128), new Issue68Budget.Usage(0, 0, 0, 0L));
            // then
            assertThat(session.budget().stageChargedNanos()).isZero();
            assertThatThrownBy(() -> session.budget().reserve(128)).hasMessage("ISSUE68_A1_N1_BUDGET_SCOPE");
        }
    }

    @Test
    void 승인Manifest_파일변조는_예약과_전송전에_차단한다() throws Exception {
        // given
        Path root = priorFixture(temporary);
        try (var session = Issue68A1N1Session.rehearsal(root); var endpoint = new Issue68FakeOpenAiEndpoint()) {
            Files.writeString(root.resolve(Issue68A1N1Plan.APPROVAL).resolve("00001-approval-manifest.json"), "{}");
            // when & then
            assertThatThrownBy(() -> session.rehearsalBoundary(endpoint.uri())).hasMessage("ISSUE68_A1_N1_APPROVAL_CHANGED");
            assertThat(endpoint.received()).isZero();
            assertThat(session.budget().stageAttempts()).isZero();
        }
    }

    @Test
    void N1도_전역잔액이_다음예약보다_작으면_실제_HTTP를_전송하지_않는다() throws Exception {
        // given: 과거 장부를 수정하지 않고 독립 global guard의 경계값만 메모리에 주입한다.
        try (var session = Issue68A1N1Session.rehearsal(priorFixture(temporary));
                var endpoint = new Issue68FakeOpenAiEndpoint(); var boundary = session.rehearsalBoundary(endpoint.uri())) {
            for (String name : List.of("charged", "stageBaselineCharge")) {
                var field = Issue68Budget.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setLong(session.budget(), 999_999_999L);
            }
            boundary.begin(List.of(Issue68A1N1Plan.IDS.get(0)), false);
            // when & then
            assertThatThrownBy(() -> boundary.rehearsalExchange(nativeRequest(endpoint.uri(), Issue68A1N1Plan.cases().get(0))))
                    .hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(endpoint.received()).isZero();
            assertThat(session.budget().stageAttempts()).isZero();
        }
    }

    @Test
    void 문맥의존_HIGH_MEDIUM은_V1오류와_V2조건부유지를_각각_기록한다() {
        // given
        var plan = new Issue68A1N1Plan();
        for (var c : Issue68A1N1Plan.cases().subList(0, 2)) {
            plan.begin(List.of(c.id()), false);
            plan.committed(c, c.expected(), true);
        }
        var c = Issue68A1N1Plan.cases().get(2);
        var medium = new com.bobfull.chat.application.result.ModerationResult(c.expected().result(), c.expected().categories(),
                com.bobfull.chat.domain.entity.RiskLevel.MEDIUM);
        // when
        plan.begin(List.of(c.id()), false);
        plan.committed(c, medium, true);
        // then: GT HIGH와 Risk Exact 오류를 삭제하지 않으며 과거 HOLD도 변경하지 않는다.
        assertThat(plan.evaluations().get(2).v1Blockers()).isNotEmpty();
        assertThat(plan.evaluations().get(2).v2Blockers()).isEmpty();
        assertThat(plan.evaluations().get(2).v2Notes()).contains("NONCRITICAL_HIGH_MEDIUM_REVIEW_PRESERVED");
        assertThat(plan.stopped()).isFalse();
    }

    private static okhttp3.Request nativeRequest(java.net.URI endpoint, Issue68EvaluationContract.Case c) {
        var json = new JsonMapper();
        var schema = Map.of("type", "json_schema", "json_schema", Map.of("name", "ModerationResult", "strict", true,
                "schema", json.readTree(Issue68EvaluationContract.schema(false))));
        var body = Map.of("model", Issue68EvaluationContract.MODEL, "max_tokens", 128, "response_format", schema,
                "messages", List.of(Map.of("role", "system", "content", Issue68RiskBoundaryPrompt.CANDIDATE),
                        Map.of("role", "user", "content", c.content())));
        return new okhttp3.Request.Builder().url(endpoint + "/chat/completions").post(okhttp3.RequestBody.create(
                json.writeValueAsString(body), okhttp3.MediaType.get("application/json"))).build();
    }
}
