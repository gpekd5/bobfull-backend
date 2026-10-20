package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PollBatchMeasurementsTest {
    @Test
    void nearest_rank는_원본_정렬_표본에서_계산한다() {
        // given
        var values = List.of(100.0, 1.0, 2.0, 3.0);
        // when / then
        assertThat(PollBatchMeasurements.percentile(values, .5)).isEqualTo(2.0);
        assertThat(PollBatchMeasurements.percentile(values, .95)).isEqualTo(100.0);
        assertThat(values).containsExactly(100.0, 1.0, 2.0, 3.0);
    }

    @Test
    void 빈_표본을_지연_0으로_변환하지_않는다() {
        // given / when / then
        assertThatThrownBy(() -> PollBatchMeasurements.percentile(List.of(), .95))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 크기_분포와_요청당_평균을_분리한다() {
        // given
        var sizes = List.of(2, 2, 5, 10);
        // when / then
        assertThat(PollBatchMeasurements.distribution(sizes)).containsEntry(2, 2L).containsEntry(5, 1L).containsEntry(10, 1L);
        assertThat(PollBatchMeasurements.mean(sizes)).isEqualTo(4.75);
    }

    @Test
    void Kafka_수신과_DB_commit을_flush_및_Repository_반환과_구분한다() {
        // given
        var sample = new PollBatchMeasurements.Sample(1, false, 10_000_000, 20_000_000,
                25_000_000, 27_000_000, 5_000_000, 100);
        // when / then
        assertThat(sample.receivedToCommitMs()).isEqualTo(15.0);
        assertThat(sample.flushReturnedNs()).isLessThan(sample.committedNs());
        assertThat(sample.repositoryReturnedNs()).isGreaterThan(sample.committedNs());
        assertThat(sample.createdToCommitMs()).isNotEqualTo(sample.receivedToCommitMs());
    }
}
