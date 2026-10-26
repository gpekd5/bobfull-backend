package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Issue68ExpandedBudgetTest {
    @TempDir Path temporary;

    @Test
    void 기존_예약과_정산과_gross를_보존하고_확장단계만_append한다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        String original = Files.readString(path);
        // when
        try (var budget = start(path, Issue68ExpandedPlan.Stage.PILOT)) {
            assertThat(budget.chargedNanos()).isEqualTo(19_587_900L);
            assertThat(budget.grossReservedNanos()).isEqualTo(57_907_200L);
            var ticket = budget.reserve(128);
            budget.settle(ticket, new Issue68Budget.Usage(824, 13, 837, 0L));
            // then
            assertThat(budget.chargedNanos()).isEqualTo(19_719_300L);
            assertThat(budget.stageChargedNanos()).isEqualTo(131_400L);
            assertThat(Files.readString(path)).startsWith(original);
            assertThatThrownBy(() -> budget.settle(ticket, new Issue68Budget.Usage(824, 13, 837, 0L)))
                    .hasMessage("ISSUE68_UNKNOWN_TICKET");
        }
    }

    @Test
    void Usage불명과_crash중_예약은_재시작후에도_해제하지_않는다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        try (var budget = start(path, Issue68ExpandedPlan.Stage.PILOT)) {
            budget.settle(budget.reserve(1280), null);
            assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        // when & then
        try (var audit = Issue68Budget.auditExpandedPreparation(path)) {
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + 19_968_000L);
            assertThat(audit.stageChargedNanos()).isEqualTo(19_968_000L);
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
        assertThatThrownBy(() -> start(path, Issue68ExpandedPlan.Stage.PILOT))
                .hasMessage("ISSUE68_EXPANDED_REAPPROVAL_REQUIRED");
        Path pending = Issue68O0PreparationTest.priorFixture(temporary.resolve("pending.jsonl"));
        // 일회성 claim 이름이 충돌하지 않는 별도 fixture를 사용한다.
        Path root = Files.createDirectory(temporary.resolve("pending"));
        Files.move(pending, root.resolve("budget.jsonl"));
        try (var budget = start(root.resolve("budget.jsonl"), Issue68ExpandedPlan.Stage.PILOT)) { budget.reserve(128); }
        try (var audit = Issue68Budget.auditExpandedPreparation(root.resolve("budget.jsonl"))) {
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + 19_276_800L);
            assertThatThrownBy(() -> audit.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
        }
    }

    @Test
    void 모든_단계는_잠금과_정확한_이전완료와_별도시작을_요구한다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        assertThatThrownBy(() -> start(path, Issue68ExpandedPlan.Stage.MAIN))
                .hasMessage("ISSUE68_EXPANDED_REAPPROVAL_REQUIRED");
        // when
        for (var stage : Issue68ExpandedPlan.Stage.values()) {
            try (var budget = start(path, stage)) {
                assertThatThrownBy(() -> Issue68Budget.auditExpandedPreparation(path)).isInstanceOf(java.io.IOException.class);
                var plan = new Issue68ExpandedPlan(stage);
                assertThatThrownBy(() -> budget.completeExpandedStage(plan)).hasMessage("ISSUE68_EXPANDED_STAGE_INCOMPLETE");
                for (var unit : Issue68ExpandedPlan.matrix().stream().filter(u -> u.stage() == stage).toList()) {
                    Issue68ExpandedPlanTest.completeUnit(plan, unit);
                }
                budget.settle(budget.reserve(128), new Issue68Budget.Usage(900, 20, 920, 0L));
                budget.completeExpandedStage(plan);
                // then
                assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_OR_CONTRACT_BLOCKED");
                assertThat(budget.chargedNanos()).isLessThan(Issue68Budget.EXPANDED_LIMIT);
            }
        }
        assertThat(Files.readString(path)).contains("EXPANDED_FAKE_END");
        assertThatThrownBy(() -> start(path, Issue68ExpandedPlan.Stage.PILOT))
                .hasMessage("ISSUE68_EXPANDED_REAPPROVAL_REQUIRED");
    }

    @Test
    void 단계부담_잔액보다_다음예약이_크면_전송전에_거절한다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        try (var budget = start(path, Issue68ExpandedPlan.Stage.PILOT)) {
            // when: 유효한 최대 Usage를 주입한 회계 fixture이며 실제 청구가 아니다.
            for (int i = 0; i < 5; i++) {
                budget.settle(budget.reserve(1280), new Issue68Budget.Usage(128000, 1280, 129280, 0L));
            }
            // then
            assertThat(budget.stageChargedNanos()).isEqualTo(99_840_000L);
            assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(budget.stageAttempts()).isEqualTo(5);
        }
    }

    @Test
    void 전역_부담차단은_단계잔액이_있어도_우선한다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        try (var budget = start(path, Issue68ExpandedPlan.Stage.PILOT)) {
            // 독립 global guard의 경계 결함 주입. 장부 또는 과거 실제 금액을 바꾸지 않는다.
            var charged = Issue68Budget.class.getDeclaredField("charged");
            charged.setAccessible(true);
            charged.setLong(budget, 990_000_000L);
            var baseline = Issue68Budget.class.getDeclaredField("stageBaselineCharge");
            baseline.setAccessible(true);
            baseline.setLong(budget, 990_000_000L);
            // when & then
            assertThatThrownBy(() -> budget.reserve(128)).hasMessage("ISSUE68_BUDGET_LIMIT");
            assertThat(budget.stageAttempts()).isZero();
        }
    }

    @Test
    void 손상된_장부와_새장부_초기화와_다른_manifest를_거절한다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        String original = Files.readString(path);
        // when & then
        assertThatThrownBy(() -> Issue68Budget.startExpandedPreparation(path, Issue68ExpandedPlan.Stage.PILOT, "0".repeat(64)))
                .hasMessage("ISSUE68_EXPANDED_MANIFEST_CHANGED");
        assertThat(Files.readString(path)).isEqualTo(original);
        Files.writeString(path, "");
        assertThatThrownBy(() -> start(path, Issue68ExpandedPlan.Stage.PILOT))
                .hasMessage("ISSUE68_EXPANDED_REAPPROVAL_REQUIRED");
        Files.writeString(path, original.substring(0, original.length() - 1));
        assertThatThrownBy(() -> start(path, Issue68ExpandedPlan.Stage.PILOT)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void 재승인없이_장부만_과거상태로_되돌려도_일회성_claim이_재실행을_막는다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        String original = Files.readString(path);
        try (var budget = start(path, Issue68ExpandedPlan.Stage.PILOT)) { budget.reserve(128); }
        // when: 공격을 모사하는 temp fixture에 한정한다.
        Files.writeString(path, original);
        // then
        assertThatThrownBy(() -> start(path, Issue68ExpandedPlan.Stage.PILOT)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(path)).isEqualTo(original);
    }

    @Test
    void 동시예약도_단계한도와_journal순서를_원자적으로_지킨다() throws Exception {
        // given
        Path path = Issue68O0PreparationTest.priorFixture(temporary.resolve("budget.jsonl"));
        var workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        try (var budget = start(path, Issue68ExpandedPlan.Stage.PILOT)) {
            // when
            var jobs = java.util.stream.IntStream.range(0, 20).mapToObj(i -> workers.submit(() -> {
                try { budget.reserve(1280); return true; }
                catch (IllegalStateException denied) { return false; }
            })).toList();
            int admitted = 0;
            for (var job : jobs) if (job.get()) admitted++;
            // then
            assertThat(admitted).isEqualTo(5);
            assertThat(budget.stageChargedNanos()).isEqualTo(99_840_000L);
            assertThat(budget.stageAttempts()).isEqualTo(5);
        } finally {
            workers.shutdownNow();
        }
        try (var audit = Issue68Budget.auditExpandedPreparation(path)) {
            assertThat(audit.chargedNanos()).isEqualTo(19_587_900L + 99_840_000L);
        }
    }

    @Test
    void 확장_manifest는_Owner와_모든_테스트소스와_예산을_동결한다() throws Exception {
        // given & when
        var manifest = Issue68ExpandedPlan.manifest();
        // then
        assertThat(manifest).containsEntry("paidExecutionEnabled", false).containsEntry("globalBurdenNanoUsd", 1_000_000_000L);
        assertThat(manifest.get("sourceSha256").toString()).contains("SizeTimeBatchOwnerLoop.java", "Issue68OpenAiKafkaHarness.java");
        assertThat(Issue68ExpandedPlan.manifestSha256()).isEqualTo(Issue68ExpandedPlan.manifestSha256());
        assertThat(Issue68Budget.PLANNED_LIMIT).isEqualTo(500_000_000L); // 기존 O0 계약 유지
    }

    private Issue68Budget start(Path path, Issue68ExpandedPlan.Stage stage) throws Exception {
        return Issue68Budget.startExpandedPreparation(path, stage, Issue68ExpandedPlan.manifestSha256());
    }
}
