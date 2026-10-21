package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;

class ChatModerationBatchErrorHandlerTest {
    @Test
    void 공유실패는_각_partition을_seek하고_정상반환과_commit을_하지_않는다() {
        // given
        var retry = mock(ChatModerationBatchRetryProcessor.class);
        var handler = new ChatModerationBatchErrorHandler(retry, 2, 0);
        Consumer<?, ?> consumer = mock(Consumer.class);
        var first = new TopicPartition("test", 0);
        var second = new TopicPartition("test", 1);
        var records = new ConsumerRecords<>(Map.of(first, List.of(new ConsumerRecord<>("test", 0, 5, "", "")),
                second, List.of(new ConsumerRecord<>("test", 1, 9, "", ""))), Map.of());
        var failure = new ChatModerationBatchRetryProcessor.GroupRetryException(0, new IllegalStateException("FAKE"));
        // when / then
        assertThatThrownBy(() -> handler.handleBatch(failure, records, consumer, null, () -> {}))
                .isInstanceOf(KafkaException.class);
        verify(consumer).seek(first, 5);
        verify(consumer).seek(second, 9);
        verifyNoMoreInteractions(consumer);
        verifyNoInteractions(retry);
        handler.clearThreadState();
        verify(retry).clearThreadState();
    }

    @Test
    void index없는_예외도_poll전체_DLT_fallback으로_전환하지_않는다() {
        // given
        var retry = mock(ChatModerationBatchRetryProcessor.class);
        var handler = new ChatModerationBatchErrorHandler(retry, 2, 0);
        Consumer<?, ?> consumer = mock(Consumer.class);
        var partition = new TopicPartition("test", 0);
        var records = new ConsumerRecords<>(Map.of(partition, List.of(new ConsumerRecord<>("test", 0, 0, "", ""))), Map.of());
        // when / then
        assertThatThrownBy(() -> handler.handleBatch(new IllegalStateException("UNCLASSIFIED"), records, consumer, null, () -> {}))
                .isInstanceOf(KafkaException.class);
        verify(consumer).seek(partition, 0);
        verifyNoMoreInteractions(consumer);
        verifyNoInteractions(retry);
    }
}
