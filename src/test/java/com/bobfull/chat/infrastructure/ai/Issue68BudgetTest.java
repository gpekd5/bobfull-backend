package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Issue68BudgetTest {
    @TempDir
    Path temporary;

    @Test
    void 정상_Usage만_정산하고_Cache_할인은_예산에서_차감하지_않는다() throws Exception {
        // given
        Path path = temporary.resolve("budget.jsonl");
        try (var budget = new Issue68Budget(path, Issue68Budget.PLANNED_LIMIT)) {
            // when
            var ticket = budget.reserve(128);
            assertThat(Files.readString(path)).contains("RESERVE");
            budget.settle(ticket, new Issue68Budget.Usage(900, 20, 920, 800L));
            // then
            assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(900, 20));
            assertThat(Files.readString(path)).contains("SETTLE").doesNotContain("content", "Authorization");
        }
    }

    @Test
    void Usage_불명과_음수_상한초과_불일치는_최대_예약을_유지한다() throws Exception {
        // given
        try (var budget = new Issue68Budget(temporary.resolve("unknown.jsonl"), Issue68Budget.PLANNED_LIMIT)) {
            var usages = new ArrayList<Issue68Budget.Usage>();
            usages.add(null);
            usages.add(new Issue68Budget.Usage(-1, 1, 0, null));
            usages.add(new Issue68Budget.Usage(128001, 1, 128002, null));
            usages.add(new Issue68Budget.Usage(10, 129, 139, null));
            usages.add(new Issue68Budget.Usage(10, 1, 12, null));
            usages.add(new Issue68Budget.Usage(10, 1, 11, 100L));
            // when
            for (var usage : usages) {
                budget.settle(budget.reserve(128), usage);
            }
            // then
            assertThat(budget.chargedNanos()).isEqualTo(6 * Issue68Budget.cost(128000, 128));
        }
    }

    @Test
    void 동시_예약이_계획_예산을_초과하지_않는다() throws Exception {
        // given
        try (var budget = new Issue68Budget(temporary.resolve("concurrent.jsonl"), Issue68Budget.PLANNED_LIMIT)) {
            var executor = Executors.newFixedThreadPool(8);
            try {
                // when
                var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 100; i++) {
                    futures.add(executor.submit(() -> {
                        try {
                            budget.reserve(1280);
                            return true;
                        } catch (IllegalStateException blocked) {
                            return false;
                        }
                    }));
                }
                int accepted = 0;
                for (var future : futures) {
                    accepted += future.get() ? 1 : 0;
                }
                // then: 25 * 0.019968 = 0.4992. 26번째 전송은 불가능하다.
                assertThat(accepted).isEqualTo(25);
                assertThat(budget.chargedNanos()).isEqualTo(499_200_000L);
                assertThat(budget.blocked()).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void 기존_journal과_중복정산_위조_Ticket을_거절한다() throws Exception {
        // given
        Path path = temporary.resolve("existing.jsonl");
        try (var budget = new Issue68Budget(path, Issue68Budget.PLANNED_LIMIT)) {
            var ticket = budget.reserve(128);
            // when & then
            assertThatThrownBy(() -> budget.settle(new Issue68Budget.Ticket(ticket.number(), 1280, ticket.reserved()),
                    new Issue68Budget.Usage(10, 1000, 1010, null))).isInstanceOf(IllegalStateException.class);
            assertThat(budget.chargedNanos()).isEqualTo(ticket.reserved());
        }
        assertThatThrownBy(() -> new Issue68Budget(path, Issue68Budget.PLANNED_LIMIT))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test
    void journal_실패와_닫힌_실행은_다음_예약을_차단한다() throws Exception {
        // given
        var budget = new Issue68Budget(temporary.resolve("closed.jsonl"), Issue68Budget.PLANNED_LIMIT);
        budget.close();
        // when & then
        assertThatThrownBy(() -> budget.reserve(128)).isInstanceOf(IllegalStateException.class);
        assertThat(budget.attempts()).isZero();
    }

    @Test
    void 예약_journal_fsync_실패시_시도수를_늘리지_않고_차단한다() throws Exception {
        // given: 실제 FileChannel이 실패하는 조건이다. 비용 기록을 메모리 mock으로 대체하지 않는다.
        try (var budget = new Issue68Budget(temporary.resolve("io-failure.jsonl"), Issue68Budget.PLANNED_LIMIT)) {
            var field = Issue68Budget.class.getDeclaredField("journal");
            field.setAccessible(true);
            ((java.nio.channels.FileChannel) field.get(budget)).close();
            // when & then
            assertThatThrownBy(() -> budget.reserve(128)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("ISSUE68_JOURNAL_FAILED");
            assertThat(budget.attempts()).isZero();
            assertThat(budget.chargedNanos()).isZero();
            assertThat(budget.blocked()).isTrue();
        }
    }

    @Test
    void 같은_응답의_중복_정산으로_예약액을_다시_차감하지_않는다() throws Exception {
        // given
        try (var budget = new Issue68Budget(temporary.resolve("duplicate.jsonl"), Issue68Budget.PLANNED_LIMIT)) {
            var ticket = budget.reserve(128);
            var usage = new Issue68Budget.Usage(900, 20, 920, null);
            budget.settle(ticket, usage);
            // when & then
            assertThatThrownBy(() -> budget.settle(ticket, usage)).isInstanceOf(IllegalStateException.class);
            assertThat(budget.chargedNanos()).isEqualTo(Issue68Budget.cost(900, 20));
        }
    }
}
