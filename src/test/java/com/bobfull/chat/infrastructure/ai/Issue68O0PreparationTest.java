package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.port.AiModerationPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

public class Issue68O0PreparationTest {
    @TempDir
    Path temporary;

    // 실제 private journal을 읽지 않는다. 공개된 과거 금액/순서로 동일한 비밀정보 없는 fixture를 만든다.
    public static Path priorFixture(Path path) throws Exception {
        var json = new JsonMapper();
        StringBuilder text = new StringBuilder();
        String digest = "GENESIS";
        String[] events = {"RESERVE", "UNKNOWN_KEEP", "RESERVE", "SETTLE", "RESERVE", "SETTLE"};
        long[] amounts = {19_276_800L, 19_276_800L, 19_276_800L, 131_400L, 19_353_600L, 179_700L};
        for (int i = 0; i < events.length; i++) {
            String payload = "{\"event\":\"" + events[i] + "\",\"attempt\":" + (i / 2 + 1)
                    + ",\"nanoUsd\":" + amounts[i] + "}";
            digest = Issue68EvaluationContract.sha256(digest + "|" + payload);
            Map<String, String> line = new LinkedHashMap<>();
            if (i < 2) {
                line.put("sha256", digest);
                line.put("payload", payload);
            } else {
                line.put("payload", payload);
                line.put("sha256", digest);
            }
            text.append(json.writeValueAsString(line)).append('\n');
        }
        Files.writeString(path, text);
        assertThat(Issue68EvaluationContract.sha256(text.toString())).isEqualTo(Issue68Budget.O0_PRIOR_SHA256);
        return path;
    }

    @Test
    void 기존_미확정액을_이월하고_새단계의_예약과_정산만_추가한다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("budget.jsonl"));
        String prefix = Files.readString(path);
        // when
        try (var budget = Issue68Budget.startO0Preparation(path)) {
            assertThat(budget.chargedNanos()).isEqualTo(19_587_900L);
            assertThat(budget.grossReservedNanos()).isEqualTo(57_907_200L);
            var ticket = budget.reserve(128);
            budget.settle(ticket, new Issue68Budget.Usage(824, 13, 837, 0L));
            // then
            assertThat(ticket.number()).isEqualTo(4);
            assertThat(budget.stageChargedNanos()).isEqualTo(131_400L);
            assertThat(budget.chargedNanos()).isEqualTo(19_587_900L + 131_400L);
            assertThat(Files.readString(path)).startsWith(prefix);
            assertThatThrownBy(() -> budget.settle(ticket, new Issue68Budget.Usage(824, 13, 837, 0L)))
                    .hasMessage("ISSUE68_UNKNOWN_TICKET");
        }
    }

    @Test
    void 정상정산_158시도는_gross가_커져도_단계부담과_누적부담_상한을_지킨다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("normal.jsonl"));
        // when
        try (var budget = Issue68Budget.startO0Preparation(path)) {
            for (int i = 0; i < 158; i++) {
                budget.settle(budget.reserve(1280), new Issue68Budget.Usage(900, 20, 920, 0L));
            }
            // then: 이 수치는 Fake fixture이지 실제 모델 비용이 아니다.
            assertThat(budget.stageAttempts()).isEqualTo(158);
            assertThat(budget.stageChargedNanos()).isEqualTo(158 * Issue68Budget.cost(900, 20));
            assertThat(budget.stageGrossReservedNanos()).isGreaterThan(Issue68Budget.O0_STAGE_LIMIT);
            assertThat(budget.stageChargedNanos()).isLessThan(Issue68Budget.O0_STAGE_LIMIT);
            assertThat(budget.chargedNanos()).isLessThan(Issue68Budget.PLANNED_LIMIT);
        }
    }

    @Test
    void Usage불명과_진행중_예약도_단계0점1달러를_소비하며_여섯번째를_차단한다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("unknown.jsonl"));
        // when
        try (var budget = Issue68Budget.startO0Preparation(path)) {
            for (int i = 0; i < 5; i++) {
                var ticket = budget.reserve(1280);
                if (i != 4) {
                    budget.settle(ticket, null);
                }
            }
            // then
            assertThat(budget.stageChargedNanos()).isEqualTo(99_840_000L);
            assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(budget.stageAttempts()).isEqualTo(5);
        }
        try (var audit = Issue68Budget.auditO0(path)) {
            assertThat(audit.stageChargedNanos()).isEqualTo(99_840_000L);
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + 99_840_000L);
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
    }

    @Test
    void 중복실행과_재시작_새장부_손상장부를_거절하고_자동재개하지_않는다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("restart.jsonl"));
        try (var budget = Issue68Budget.startO0Preparation(path)) {
            budget.reserve(128); // 전송 유실/강제 종료 뒤에도 미확정 부담으로 남아야 한다.
            // when & then
            assertThatThrownBy(() -> Issue68Budget.startO0Preparation(path)).isInstanceOf(java.io.IOException.class);
        }
        try (var audit = Issue68Budget.auditO0(path)) {
            assertThat(audit.stageChargedNanos()).isEqualTo(19_276_800L);
            assertThat(audit.blocked()).isTrue();
        }
        assertThatThrownBy(() -> Issue68Budget.startO0Preparation(path)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> Issue68Budget.startO0Preparation(temporary.resolve("new.jsonl")))
                .hasMessage("ISSUE68_O0_PRIOR_JOURNAL_REQUIRED");
        assertThat(Files.exists(temporary.resolve("new.jsonl"))).isFalse();
        Files.writeString(path, "corrupt\n");
        assertThatThrownBy(() -> Issue68Budget.auditO0(path)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void 과거_prefix로_되돌린_journal도_별도_claim이_있으면_새단계를_시작하지_않는다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("rollback.jsonl"));
        String prefix = Files.readString(path);
        try (var budget = Issue68Budget.startO0Preparation(path)) {
            budget.settle(budget.reserve(128), null);
        }
        // when: fixture에서만 rollback을 재현하며 실제 journal은 건드리지 않는다.
        Files.writeString(path, prefix);
        // then
        assertThatThrownBy(() -> Issue68Budget.startO0Preparation(path)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(path)).isEqualTo(prefix);
    }

    @Test
    void HTTP_632회_이후에는_정상정산으로_가용액이_있어도_차단한다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("http-cap.jsonl"));
        // when
        try (var budget = Issue68Budget.startO0Preparation(path)) {
            for (int i = 0; i < Issue68Budget.O0_HTTP_LIMIT; i++) {
                budget.settle(budget.reserve(128), new Issue68Budget.Usage(0, 0, 0, 0L));
            }
            // then
            assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(budget.stageAttempts()).isEqualTo(632);
        }
    }

    @Test
    void 동시_예약도_전송전_원자적으로_상한을_검사한다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("concurrent.jsonl"));
        try (var budget = Issue68Budget.startO0Preparation(path)) {
            var executor = Executors.newFixedThreadPool(8);
            try {
                // when
                List<java.util.concurrent.Future<Boolean>> work = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    work.add(executor.submit(() -> {
                        try {
                            budget.reserve(1280);
                            return true;
                        } catch (IllegalStateException rejected) {
                            return false;
                        }
                    }));
                }
                int accepted = 0;
                for (var item : work) {
                    accepted += item.get() ? 1 : 0;
                }
                // then
                assertThat(accepted).isEqualTo(5);
                assertThat(budget.stageChargedNanos()).isEqualTo(99_840_000L);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void 실제_SDK_Retry는_HTTP별_예약하며_불명Usage후_다음논리를_차단한다() throws Exception {
        // given
        Path path = priorFixture(temporary.resolve("sdk.jsonl"));
        var plan = new Issue68O0Plan();
        try (var budget = Issue68Budget.startO0Preparation(path);
                var endpoint = new Issue68FakeOpenAiEndpoint();
                var boundary = Issue68HttpBoundary.o0Preparation(endpoint.uri(), budget, plan)) {
            endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.RATE_LIMIT, Issue68FakeOpenAiEndpoint.Mode.OK);
            var c = Issue68EvaluationContract.heldout().get(0);
            Issue68OpenAiContext.runner(boundary, endpoint.uri().toString(), false).run(context -> {
                var logical = boundary.begin(List.of(c.id()), false);
                // when
                context.getBean(AiModerationPort.class).analyze(c.content());
                boundary.end(logical, false);
                // then
                assertThat(endpoint.received()).isEqualTo(2);
                assertThat(budget.stageAttempts()).isEqualTo(2);
                assertThat(budget.stageChargedNanos()).isGreaterThan(19_276_800L);
                assertThat(plan.stopped()).isTrue();
                assertThatThrownBy(() -> boundary.begin(List.of("HOLDOUT-SAFE-02"), false))
                        .hasMessage("ISSUE68_O0_LOGICAL_CONTRACT");
                assertThat(endpoint.received()).isEqualTo(2);
            });
        }
    }

    @Test
    void O0준비는_HTTPS허용이나_Live승인을_넓히지_않는다() throws Exception {
        // given
        try (var budget = Issue68Budget.startO0Preparation(priorFixture(temporary.resolve("https.jsonl")))) {
            // when & then
            assertThatThrownBy(() -> Issue68HttpBoundary.o0Preparation(java.net.URI.create("https://api.openai.com/v1"),
                    budget, new Issue68O0Plan())).hasMessage("ISSUE68_LIVE_ENDPOINT_NOT_AUTHORIZED");
        }
    }
}
