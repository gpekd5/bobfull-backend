package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchListenerFailedException;

class ChatModerationBatchRetryProcessorTest {
    private final ChatModerationBatchService service = mock(ChatModerationBatchService.class);
    private final ChatModerationDltRecoverer recoverer = mock(ChatModerationDltRecoverer.class);
    private final ChatModerationBatchRetryProcessor retry = new ChatModerationBatchRetryProcessor(service, recoverer, 2);
    private final RuntimeException providerFailure = new IllegalStateException("FAKE_PROVIDER_FAILURE");

    @Test
    void 공유요청_실패만_같은_두번의_예산을_소비한다() {
        // given
        var records = List.of(record(0, 0), record(0, 1));
        failAll();
        // when / then
        pending(records);
        retry.analyze(records);
        verify(service, times(2)).analyze(List.of(1L, 2L));
        verify(recoverer, times(2)).accept(any(), same(providerFailure));
        retry.analyze(records); // commit 전 재전달도 추가 Provider/DLT 없음
        verify(service, times(2)).analyze(any());
        verifyNoMoreInteractions(recoverer);
    }

    @Test
    void 나뉜_poll과_새_메시지는_기존_실패그룹의_예산에_섞지_않는다() {
        // given
        var first = record(0, 0);
        var second = record(1, 1);
        failAll();
        pending(List.of(first, second));
        doNothing().when(service).analyze(List.of(3L));
        // when
        retry.analyze(List.of(first, record(0, 2)));
        retry.analyze(List.of(second));
        // then
        verify(service).analyze(List.of(1L));
        verify(service).analyze(List.of(2L));
        verify(service).analyze(List.of(3L));
        verify(recoverer).accept(same(first), any());
        verify(recoverer).accept(same(second), any());
        verify(recoverer, times(2)).accept(any(), any());
    }

    @Test
    void 부분_항목실패는_원래_poll_index로_전달하고_공유복구하지_않는다() {
        // given
        doThrow(new BatchModerationException(1, new IllegalStateException("VALIDATION")))
                .when(service).analyze(List.of(1L, 2L, 3L));
        var records = List.of(record(0, 0), record(2, 1), record(0, 2));
        // when / then
        assertThatThrownBy(() -> retry.analyze(records)).isInstanceOfSatisfying(BatchListenerFailedException.class,
                failure -> assertThat(failure.getIndex()).isEqualTo(1));
        verifyNoInteractions(recoverer);
        assertThat(retry.isRedelivery(records.get(1))).isFalse();
    }

    @Test
    void Rule과_성공_제외후_실제로_실패한_요청_항목만_복구한다() {
        // given: index0 Rule 완료, index2 저장 완료, index1/3만 요청 실패
        var error = new BatchModerationException(Map.of(1, providerFailure, 3, providerFailure), List.of(List.of(1, 3)));
        doThrow(error).when(service).analyze(List.of(1L, 2L, 3L, 4L));
        doThrow(new BatchModerationException(Map.of(0, providerFailure, 1, providerFailure), List.of(List.of(0, 1))))
                .when(service).analyze(List.of(2L, 4L));
        var records = List.of(record(0, 0), record(1, 1), record(0, 2), record(1, 3));
        // when
        pending(records);
        retry.analyze(records);
        // then
        verify(recoverer).accept(same(records.get(1)), any());
        verify(recoverer).accept(same(records.get(3)), any());
        verify(recoverer, times(2)).accept(any(), any());
    }

    @Test
    void 복구_실패는_Provider를_다시_호출하지_않고_성공한_형제복구를_반복하지_않는다() {
        // given
        failAll();
        var records = List.of(record(0, 0), record(1, 1));
        doThrow(new IllegalStateException("DLT_OR_FINAL_DB_FAILURE")).doNothing()
                .when(recoverer).accept(same(records.get(0)), any());
        // when
        pending(records);
        pending(records);
        retry.analyze(records);
        // then
        verify(service, times(2)).analyze(any());
        verify(recoverer, times(2)).accept(same(records.get(0)), any());
        verify(recoverer, times(1)).accept(same(records.get(1)), any());
    }

    @Test
    void 공유실패_후_다른_Consumer가_완료한_메시지는_DLT하지_않는다() {
        // given
        failAll();
        var records = List.of(record(0, 0), record(0, 1));
        pending(records);
        when(service.isCompleted(1L)).thenReturn(true);
        // when
        retry.analyze(records);
        // then
        verify(recoverer, never()).accept(same(records.get(0)), any());
        verify(recoverer).accept(same(records.get(1)), any());
    }

    @Test
    void 실제_offset_commit_전에는_복구표식을_버리지_않는다() {
        // given
        failAll();
        var records = List.of(record(0, 0), record(0, 1));
        pending(records);
        retry.analyze(records);
        Consumer<?, ?> consumer = mock(Consumer.class);
        var partition = new TopicPartition("test", 0);
        when(consumer.committed(Set.of(partition))).thenReturn(Map.of(partition, new OffsetAndMetadata(1)));
        // when
        retry.pruneCommitted(consumer);
        // then
        assertThat(retry.isRedelivery(records.get(0))).isFalse();
        assertThat(retry.isRedelivery(records.get(1))).isTrue();
    }

    @Test
    void revoke된_partition만_제거하고_thread_종료시_모든_상태를_버린다() {
        // given
        failAll();
        var records = List.of(record(0, 0), record(1, 1));
        pending(records);
        // when / then
        retry.revoke(List.of(new TopicPartition("test", 0)));
        assertThat(retry.isRedelivery(records.get(0))).isFalse();
        assertThat(retry.isRedelivery(records.get(1))).isTrue();
        retry.clearThreadState();
        assertThat(retry.isRedelivery(records.get(1))).isFalse();
    }

    @Test
    void 같은_좌표라도_서로다른_Consumer_thread의_예산은_분리된다() throws Exception {
        // given
        failAll();
        var records = List.of(record(0, 0));
        pending(records);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // when / then
            executor.submit(() -> {
                assertThat(retry.isRedelivery(records.get(0))).isFalse();
                pending(records);
                retry.clearThreadState();
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(retry.isRedelivery(records.get(0))).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void indexed_소진후_복구실패도_Provider로_되돌아가지_않는다() {
        // given
        var record = record(0, 0);
        doThrow(new IllegalStateException("FINAL_DB_FAILURE")).doNothing().when(recoverer).accept(same(record), any());
        // when
        assertThatThrownBy(() -> retry.recoverIndexed(record, providerFailure)).isInstanceOf(IllegalStateException.class);
        retry.analyze(List.of(record));
        // then
        verify(service, never()).analyze(any());
        verify(recoverer, times(2)).accept(same(record), same(providerFailure));
    }

    private void failAll() {
        doAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            Map<Integer, RuntimeException> errors = new java.util.LinkedHashMap<>();
            List<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                errors.put(i, providerFailure);
                indexes.add(i);
            }
            throw new BatchModerationException(errors, List.of(indexes));
        }).when(service).analyze(any());
    }

    private void pending(List<ConsumerRecord<Object, Object>> records) {
        assertThatThrownBy(() -> retry.analyze(records)).isInstanceOf(ChatModerationBatchRetryProcessor.GroupRetryException.class);
    }

    private ConsumerRecord<Object, Object> record(int partition, long offset) {
        return new ConsumerRecord<>("test", partition, offset, "key",
                new ChatMessageCreatedEvent("synthetic", 1, offset + 1, 1L, Instant.EPOCH));
    }
}
