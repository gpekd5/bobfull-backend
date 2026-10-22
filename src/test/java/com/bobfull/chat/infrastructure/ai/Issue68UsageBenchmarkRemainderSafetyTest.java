package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class Issue68UsageBenchmarkRemainderSafetyTest {
    @TempDir Path temporary;
    private static final Issue68UsageBenchmarkPlan.Stage REST = Issue68UsageBenchmarkPlan.Stage.REMAINDER;

    // 공개된 Burst Usage/예약액만으로 과거 이벤트를 복원한다. 실제 private 장부에는 접근하지 않는다.
    static Path completedBurstFixture(Path root) throws Exception {
        Issue68UsageBenchmarkSafetyTest.priorFixture(root);
        Path file = root.resolve("budget.jsonl");
        append(file, "{\"stage\":\"BURST\",\"nanoUsd\":0,\"manifestSha256\":\""
                + Issue68UsageBenchmarkPlan.COMPLETED_BURST_MANIFEST_SHA
                + "\",\"event\":\"USAGE_BENCHMARK_BEGIN\",\"attempt\":5,\"priorJournalSha256\":\""
                + Issue68UsageBenchmarkPlan.PRIOR_SHA + "\"}");
        var json = new JsonMapper();
        var attempts = new java.util.TreeMap<Integer, tools.jackson.databind.JsonNode>();
        for (var run : json.readTree(Files.readString(Path.of(Issue68UsageBenchmarkPlan.COMPLETED_BURST_RESULTS))).path("runs")) {
            for (var attempt : run.path("http")) attempts.put(attempt.path("number").asInt(), attempt);
        }
        assertThat(attempts).hasSize(31);
        for (var entry : attempts.entrySet()) {
            var attempt = entry.getValue();
            append(file, "{\"event\":\"RESERVE\",\"attempt\":" + entry.getKey()
                    + ",\"nanoUsd\":" + attempt.path("reservedNanos").asLong() + "}");
            append(file, "{\"event\":\"SETTLE\",\"attempt\":" + entry.getKey() + ",\"nanoUsd\":"
                    + Issue68Budget.cost(attempt.path("usage").path("input").asLong(), attempt.path("usage").path("output").asLong()) + "}");
        }
        append(file, "{\"stage\":\"BURST\",\"attempt\":36,\"nanoUsd\":0,\"event\":\"USAGE_BENCHMARK_END\"}");
        assertThat(Issue68EvaluationContract.sha256(Files.readString(file))).isEqualTo(Issue68UsageBenchmarkPlan.COMPLETED_BURST_JOURNAL_SHA);
        return root.toRealPath();
    }

    private static void append(Path file, String payload) throws Exception {
        var json = new JsonMapper();
        String priorDigest = json.readTree(Files.readString(file).lines().reduce((a, b) -> b).orElseThrow()).path("sha256").asText();
        var line = new LinkedHashMap<String, String>();
        line.put("sha256", Issue68EvaluationContract.sha256(priorDigest + "|" + payload));
        line.put("payload", payload);
        Files.writeString(file, json.writeValueAsString(line) + "\n", StandardOpenOption.APPEND);
    }

    @Test void 별도승인없이는_장부_Key_접근전에_차단한다() {
        assertThatThrownBy(Issue68UsageBenchmarkSession::openRemainder).hasMessage("ISSUE68_BENCHMARK_PAID_APPROVAL_REQUIRED");
    }

    @Test void 완료Burst와_UNKNOWN을_복원하고_별도360단계만_시작한다() throws Exception {
        Path root = completedBurstFixture(temporary);
        Path file = root.resolve("budget.jsonl");
        String old = Files.readString(file);
        try (var budget = Issue68Budget.startBenchmarkLive(file, REST, Issue68UsageBenchmarkPlan.manifestSha256(REST))) {
            assertThat(budget.chargedNanos()).isEqualTo(25_923_000L);
            assertThat(budget.grossReservedNanos()).isEqualTo(700_876_800L);
            assertThat(budget.benchmarkChargedNanos()).isEqualTo(6_041_250L);
            assertThat(budget.stageChargedNanos()).isZero();
            assertThat(budget.stageAttempts()).isZero();
            assertThatThrownBy(() -> Issue68Budget.auditBenchmark(file)).isInstanceOf(java.io.IOException.class);
        }
        assertThat(Files.readString(file)).startsWith(old);
        try (var restored = Issue68Budget.auditBenchmark(file)) {
            assertThat(restored.chargedNanos()).isEqualTo(25_923_000L);
            assertThat(restored.blocked()).isTrue();
            assertThatThrownBy(() -> restored.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        assertThatThrownBy(() -> Issue68Budget.startBenchmarkLive(file, REST, Issue68UsageBenchmarkPlan.manifestSha256(REST)))
                .isInstanceOf(java.io.IOException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"UNFINISHED", "PRIOR_ONLY", "ALTERED", "WRONG_MANIFEST"})
    void 완료이력이나_승인불일치는_claim_예약_전송전에_차단한다(String fault) throws Exception {
        Path root = fault.equals("PRIOR_ONLY") ? Issue68UsageBenchmarkSafetyTest.priorFixture(temporary) : completedBurstFixture(temporary);
        Path file = root.resolve("budget.jsonl");
        if (fault.equals("UNFINISHED")) {
            var lines = Files.readAllLines(file);
            Files.writeString(file, String.join("\n", lines.subList(0, lines.size() - 1)) + "\n");
        }
        if (fault.equals("ALTERED")) Files.writeString(file, "corrupt\n", StandardOpenOption.APPEND);
        String before = Files.readString(file);
        assertThatThrownBy(() -> Issue68Budget.startBenchmarkLive(file, REST,
                fault.equals("WRONG_MANIFEST") ? "0".repeat(64) : Issue68UsageBenchmarkPlan.manifestSha256(REST)))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(Files.exists(root.resolve(REST.approval() + ".claim"))).isFalse();
    }

    @Test void 실제HTTPS경계는_1440회_계약만_허용하며_실전송은_하지않는다() throws Exception {
        try (var session = Issue68UsageBenchmarkSession.rehearsal(completedBurstFixture(temporary), REST)) {
            session.plan().start(Issue68UsageBenchmarkPlan.units(REST).get(0));
            try (var boundary = session.httpsInspectionBoundary()) {
                var c = Issue68UsageBenchmarkPlan.cases().get(0);
                boundary.begin(List.of(c.id()), false);
                boundary.verifyRequest(Issue68UsageBenchmarkSafetyTest.request("https://api.openai.com/v1", c));
                assertThat(session.budget().stageAttempts()).isZero();
                assertThatThrownBy(() -> boundary.fakeMonitoring(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new, r -> { }))
                        .hasMessage("ISSUE68_FAKE_MONITORING_ONLY");
            }
        }
    }

    @Test void 열여덟조건360건과_논리당4회_단계1440회를_고정한다() {
        var plan = new Issue68UsageBenchmarkPlan(REST);
        assertThat(Issue68UsageBenchmarkPlan.units(REST)).hasSize(18)
                .noneMatch(u -> u.traffic() == Issue68ExpandedPlan.Traffic.BURST);
        assertThat(REST.httpLimit).isEqualTo(1440);
        assertThat(REST.burdenLimit).isEqualTo(400_000_000L);
        int attempts = 0;
        for (var unit : Issue68UsageBenchmarkPlan.units(REST)) {
            plan.start(unit);
            int local = 0;
            for (var c : Issue68UsageBenchmarkPlan.cases()) {
                plan.begin(List.of(c.id()), false);
                for (int i = 0; i < 4; i++) { plan.beforeHttp(local + 1); attempts++; }
                local++; plan.committed(c, c.expected(), true);
            }
            plan.completeUnit(true);
        }
        assertThat(attempts).isEqualTo(1440);
        assertThat(plan.logicalCount()).isEqualTo(360);
        assertThat(plan.complete()).isTrue();
        assertThatThrownBy(() -> plan.beforeHttp(20)).hasMessage("ISSUE68_BENCHMARK_HTTP_LIMIT");
    }

    @Test void UNKNOWN은_기존예약을_보존하고_동일논리Retry도_차단한다() throws Exception {
        try (var session = Issue68UsageBenchmarkSession.rehearsal(completedBurstFixture(temporary), REST);
                var endpoint = new Issue68FakeOpenAiEndpoint()) {
            session.plan().start(Issue68UsageBenchmarkPlan.units(REST).get(0));
            try (var boundary = session.rehearsalBoundary(endpoint.uri())) {
                var c = Issue68UsageBenchmarkPlan.cases().get(0);
                boundary.begin(List.of(c.id()), false);
                var request = Issue68UsageBenchmarkSafetyTest.request(endpoint.uri().toString(), c);
                endpoint.enqueue(Issue68FakeOpenAiEndpoint.Mode.NO_USAGE, Issue68FakeOpenAiEndpoint.Mode.OK);
                assertThatThrownBy(() -> boundary.rehearsalExchange(request)).isInstanceOf(java.io.IOException.class);
                assertThatThrownBy(() -> boundary.rehearsalExchange(request)).isInstanceOf(IllegalStateException.class);
                assertThat(endpoint.received()).isEqualTo(1);
                assertThat(session.budget().chargedNanos()).isEqualTo(25_923_000L + Issue68Budget.cost(128000, 128));
            }
        }
    }

    @Test void 신규40센트예산은_과거부담과_별도계산하고_초과예약을_기록하지않는다() throws Exception {
        Path root = completedBurstFixture(temporary);
        Path file = root.resolve("budget.jsonl");
        try (var budget = Issue68Budget.startBenchmarkLive(file, REST, Issue68UsageBenchmarkPlan.manifestSha256(REST))) {
            for (int i = 0; i < 20; i++) budget.settle(budget.reserve(128), new Issue68Budget.Usage(128000, 128, 128128, 0L));
            assertThat(budget.stageChargedNanos()).isEqualTo(385_536_000L);
            String before = Files.readString(file);
            assertThatThrownBy(() -> budget.reserve(128)).isInstanceOf(IllegalStateException.class);
            assertThat(Files.readString(file)).isEqualTo(before);
            assertThat(budget.chargedNanos()).isEqualTo(411_459_000L);
        }
    }

    @Test void 승인후보Manifest는_Burst실측과_현재잔액에_연결하고_자동실행하지않는다() throws Exception {
        var manifest = Issue68UsageBenchmarkPlan.manifest(REST);
        assertThat(manifest).containsEntry("priorJournalSha256", Issue68UsageBenchmarkPlan.COMPLETED_BURST_JOURNAL_SHA)
                .containsEntry("priorBurdenNanoUsd", 25_923_000L).containsEntry("priorUnknownNanoUsd", 19_276_800L)
                .containsEntry("automaticNextStage", false).containsEntry("requiresSeparateHumanPaidApproval", true);
        Files.createDirectories(Path.of("build/issue68"));
        Files.writeString(Path.of("build/issue68/usage-benchmark-remainder-approval-proposal.json"), new JsonMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paidExecutionPerformed", false,
                        "manifestSha256", Issue68UsageBenchmarkPlan.manifestSha256(REST), "manifest", manifest)));
    }
}
