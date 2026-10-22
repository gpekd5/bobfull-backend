package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Issue68LiveReadinessTest {
    @TempDir
    Path temporary;

    @Test
    void 재시작시_정산액과_미확정_예약을_복원한다() throws Exception {
        // given
        Path journal = temporary.resolve("budget.jsonl");
        try (var first = Issue68Budget.restoreSmoke(journal)) {
            first.settle(first.reserve(128), new Issue68Budget.Usage(1000, 20, 1020, 0L));
            first.reserve(256); // 응답을 받기 전에 프로세스가 사라진 경계
        }
        // when
        try (var restored = Issue68Budget.restoreSmoke(journal)) {
            // then
            assertThat(restored.attempts()).isEqualTo(2);
            assertThat(restored.chargedNanos()).isEqualTo(Issue68Budget.cost(1000, 20) + Issue68Budget.cost(128000, 256));
            assertThat(restored.grossReservedNanos()).isEqualTo(Issue68Budget.cost(128000, 128) + Issue68Budget.cost(128000, 256));
        }
    }

    @Test
    void 정산으로_가용액이_늘어도_누적예약_십센트_상한을_넘지_않는다() throws Exception {
        // given
        try (var budget = Issue68Budget.restoreSmoke(temporary.resolve("gross.jsonl"))) {
            // when
            for (int i = 0; i < 5; i++) {
                budget.settle(budget.reserve(128), new Issue68Budget.Usage(900, 20, 920, 0L));
            }
            // then
            assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(budget.grossReservedNanos()).isLessThanOrEqualTo(100_000_000L);
        }
    }

    @Test
    void 중복_실행은_장부_잠금으로_차단한다() throws Exception {
        // given
        Path path = temporary.resolve("locked.jsonl");
        try (var first = Issue68Budget.restoreSmoke(path)) {
            first.reserve(128);
            // when & then
            assertThatThrownBy(() -> Issue68Budget.restoreSmoke(path)).isInstanceOf(java.io.IOException.class);
            assertThat(first.attempts()).isEqualTo(1);
        }
    }

    @Test
    void 장부_손상과_잘린_마지막_기록은_전송없이_거절한다() throws Exception {
        // given
        Path path = temporary.resolve("corrupt.jsonl");
        try (var first = Issue68Budget.restoreSmoke(path)) {
            first.reserve(128);
        }
        String valid = Files.readString(path);
        // when & then
        Files.writeString(path, valid.replace("RESERVE", "RESERVX"));
        assertThatThrownBy(() -> Issue68Budget.restoreSmoke(path)).isInstanceOf(java.io.IOException.class);
        Files.writeString(path, valid.substring(0, valid.length() - 1));
        assertThatThrownBy(() -> Issue68Budget.restoreSmoke(path)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void 동일_승인_재실행과_장부만_삭제한_예산초기화는_차단한다() throws Exception {
        // given
        Path directory = temporary.resolve("session");
        try (var session = new Issue68LiveSession(directory)) {
            session.budget().reserve(128);
        }
        // when & then
        assertThatThrownBy(() -> new Issue68LiveSession(directory)).hasMessage("ISSUE68_REAPPROVAL_REQUIRED");
        Files.delete(directory.resolve("budget.jsonl"));
        assertThatThrownBy(() -> new Issue68LiveSession(directory)).hasMessage("ISSUE68_JOURNAL_MISSING_REAPPROVAL_REQUIRED");
    }

    @Test
    void Live는_지정된_Native1과_Batch1만_허용한다() throws Exception {
        // given
        try (var session = new Issue68LiveSession(temporary.resolve("scopes")); var boundary = session.boundary()) {
            var ids = Issue68LiveSession.smokeCases().stream().map(Issue68EvaluationContract.Case::id).toList();
            // when
            var nativeCall = boundary.begin(ids.subList(0, 1), false);
            boundary.end(nativeCall, false);
            var batchCall = boundary.begin(ids, true);
            boundary.end(batchCall, false);
            // then
            assertThatThrownBy(() -> boundary.begin(ids, true)).hasMessage("ISSUE68_LOGICAL_LIMIT");
            assertThat(boundary.attempts()).isEmpty();
        }
    }

    @Test
    void checkpoint_실패는_다음_논리요청을_막는다() throws Exception {
        // given
        try (var endpoint = new Issue68FakeOpenAiEndpoint();
                var budget = new Issue68Budget(temporary.resolve("snapshot.jsonl"), Issue68Budget.PLANNED_LIMIT);
                var boundary = new Issue68HttpBoundary(endpoint.uri(), budget)) {
            boundary.checkpoints((name, value) -> { throw new IllegalStateException("sensitive-error-not-preserved"); });
            // when & then
            assertThatThrownBy(() -> boundary.checkpoint("x", List.of())).hasMessage("ISSUE68_CHECKPOINT_FAILED")
                    .hasNoCause();
            assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-01"), false)).hasMessage("ISSUE68_EXECUTION_STOPPED");
        }
    }

    @Test
    void Live목적지는_정확한_HTTPS_endpoint만_허용한다() throws Exception {
        // given
        try (var budget = new Issue68Budget(temporary.resolve("urls.jsonl"), Issue68Budget.PLANNED_LIMIT)) {
            // when & then
            for (String url : List.of("http://api.openai.com/v1", "https://api.openai.com.evil/v1",
                    "https://api.openai.com:444/v1", "https://api.openai.com/v1?x=y", "https://user@api.openai.com/v1")) {
                assertThatThrownBy(() -> new Issue68HttpBoundary(java.net.URI.create(url), budget, true))
                        .hasMessage("ISSUE68_LIVE_ENDPOINT_NOT_AUTHORIZED");
            }
            assertThat(budget.attempts()).isZero();
        }
    }
    @Test
    void Live_요청의_Prompt_Schema_Model_cap_옵션_변조를_전송전에_막는다() throws Exception {
        // given
        try (var session = new Issue68LiveSession(temporary.resolve("contract")); var boundary = session.boundary()) {
            var c = Issue68LiveSession.smokeCases().get(0);
            var json = new tools.jackson.databind.json.JsonMapper();
            var body = json.createObjectNode();
            body.put("model", "gpt-4o-mini");
            body.put("max_tokens", 128);
            body.set("messages", json.valueToTree(List.of(java.util.Map.of("role", "system", "content", ModerationPrompt.SYSTEM_PROMPT),
                    java.util.Map.of("role", "user", "content", c.content()))));
            body.set("response_format", json.valueToTree(java.util.Map.of("type", "json_schema", "json_schema",
                    java.util.Map.of("schema", json.readTree(Issue68EvaluationContract.schema(false))))));
            var logical = boundary.begin(List.of(c.id()), false);
            // when & then: 승인된 요청도 이 테스트에서는 네트워크를 사용하지 않는다.
            boundary.verifyRequest(request(body.toString()));
            for (String field : List.of("model", "max_tokens", "temperature", "stream", "response_format", "messages")) {
                var changed = body.deepCopy();
                switch (field) {
                    case "model" -> changed.put(field, "different-model");
                    case "max_tokens" -> changed.put(field, 129);
                    case "temperature" -> changed.put(field, 0.5);
                    case "stream" -> changed.put(field, true);
                    case "response_format" -> changed.set(field, json.readTree("{\"type\":\"json_schema\",\"json_schema\":{\"schema\":{}}}"));
                    default -> changed.set(field, json.valueToTree(List.of(java.util.Map.of("role", "system", "content", "changed"))));
                }
                assertThatThrownBy(() -> boundary.verifyRequest(request(changed.toString()))).isInstanceOf(IllegalStateException.class);
            }
            boundary.end(logical, false);
            assertThat(boundary.attempts()).isEmpty();
        }
    }

    private static okhttp3.Request request(String body) {
        return new okhttp3.Request.Builder().url("https://api.openai.com/v1/chat/completions")
                .post(okhttp3.RequestBody.create(body, okhttp3.MediaType.get("application/json"))).build();
    }

    @Test
    void 오류_원문은_제거하고_HTTP_status와_Retry_헤더는_보존한다() throws Exception {
        // given
        var response = new okhttp3.Response.Builder().request(request("{}"))
                .protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("rate limit")
                .header("Retry-After", "1").body(okhttp3.ResponseBody.create("sensitive-canary",
                        okhttp3.MediaType.get("application/json"))).build();
        // when
        try (var sanitized = Issue68HttpBoundary.redactError(response)) {
            // then
            assertThat(sanitized.code()).isEqualTo(429);
            assertThat(sanitized.header("Retry-After")).isEqualTo("1");
            assertThat(sanitized.body().string()).contains("REDACTED").doesNotContain("sensitive-canary");
        }
    }

    @Test
    void 동결_요청_해시가_다르면_추가옵션을_포함해_거절한다() {
        // given & when & then
        Issue68LiveSession.verifyFrozenRequest(false, Issue68LiveSession.NATIVE_REQUEST_SHA256);
        Issue68LiveSession.verifyFrozenRequest(true, Issue68LiveSession.BATCH_REQUEST_SHA256);
        assertThatThrownBy(() -> Issue68LiveSession.verifyFrozenRequest(false, Issue68LiveSession.BATCH_REQUEST_SHA256))
                .hasMessage("ISSUE68_FROZEN_REQUEST_CHANGED");
    }
}
