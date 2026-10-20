package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FakeAiModerationBatchAdapterTest {
    @ParameterizedTest
    @CsvSource({"2,SAFE", "10,SAFE", "2,FLAGGED", "10,FLAGGED"})
    void 설정한_결과와_중립_ID를_항목별로_반환한다(int size, ModerationResultType type) {
        // given
        var input = items(size);
        var adapter = new FakeAiModerationBatchAdapter(0, type);
        // when
        var response = adapter.analyze(input);
        // then
        assertThat(response.provider()).isEqualTo("Fake");
        assertThat(response.model()).isEqualTo("fake-model");
        assertThat(response.results()).extracting(AiModerationBatchPort.Output::id)
                .containsExactlyElementsOf(input.stream().map(AiModerationBatchPort.Item::id).toList());
        response.results().forEach(output -> {
            assertThat(output.moderation().result()).isEqualTo(type);
            assertThat(output.moderation().categories()).isEqualTo(type == ModerationResultType.SAFE
                    ? Set.of() : Set.of(ModerationCategory.PROFANITY));
            assertThat(output.moderation().riskLevel()).isEqualTo(type == ModerationResultType.SAFE
                    ? RiskLevel.LOW : RiskLevel.HIGH);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 10})
    void 한_항목의_강제실패는_Batch_요청_전체로_전파된다(int size) {
        // given
        var input = new java.util.ArrayList<>(items(size));
        input.set(size - 1, new AiModerationBatchPort.Item("last", FakeAiModerationAdapter.FORCE_FAIL_MARKER));
        var adapter = new FakeAiModerationBatchAdapter(0, ModerationResultType.SAFE);
        // when / then
        assertThatThrownBy(() -> adapter.analyze(input)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Fake AI 강제 실패(실험 C 격리 테스트)");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 11})
    void 실제_Batch_Adapter와_동일한_크기_경계를_적용한다(int size) {
        // given
        var adapter = new FakeAiModerationBatchAdapter(0, ModerationResultType.SAFE);
        // when / then
        assertThatThrownBy(() -> adapter.analyze(items(size))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 열건이어도_지연은_요청당_한번만_적용한다() {
        // given: 단건 Fake N회라면 2초가 필요하다. 성능 비교가 아닌 잘못된 N회 지연 회귀 방지다.
        var adapter = new FakeAiModerationBatchAdapter(200, ModerationResultType.SAFE);
        long started = System.nanoTime();
        // when
        var response = assertTimeout(Duration.ofMillis(1500), () -> adapter.analyze(items(10)));
        // then
        assertThat(response.results()).hasSize(10);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 지연중_인터럽트는_복원하고_요청_실패로_전파한다() {
        // given
        var adapter = new FakeAiModerationBatchAdapter(200, ModerationResultType.SAFE);
        Thread.currentThread().interrupt();
        try {
            // when / then
            assertThatThrownBy(() -> adapter.analyze(items(2))).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static List<AiModerationBatchPort.Item> items(int size) {
        return IntStream.range(0, size).mapToObj(index ->
                new AiModerationBatchPort.Item("m%03d".formatted(index + 1), "식사 일정 확인해요")).toList();
    }
}
