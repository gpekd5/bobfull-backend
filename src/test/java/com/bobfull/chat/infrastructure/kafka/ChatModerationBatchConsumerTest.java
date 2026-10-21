package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.apache.kafka.clients.consumer.Consumer;

class ChatModerationBatchConsumerTest {
    private final ChatModerationBatchService service = mock(ChatModerationBatchService.class);
    private final ChatModerationBatchConsumer consumer = new ChatModerationBatchConsumer(
            new ChatModerationBatchRetryProcessor(service, mock(ChatModerationDltRecoverer.class), 3),
            mock(ChatModerationBatchMetrics.class), mock(ChatModerationMetrics.class), mock(DefaultErrorHandler.class));

    @Test
    void Partition과_무관하게_원래_poll_index를_ErrorHandler에_전달한다() {
        // given
        var ids = List.of(11L, 22L, 33L);
        doThrow(new BatchModerationException(1, new IllegalStateException("DB_FAILURE"))).when(service).analyze(ids);
        // when / then
        assertThatThrownBy(() -> consumer.onMessages(List.of(record(0, 11, 1), record(2, 22, 1), record(0, 33, 1)), mock(Consumer.class)))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, failure -> assertThat(failure.getIndex()).isEqualTo(1));
    }

    @Test
    void 잘못된_Event_앞의_prefix를_처리한_뒤에만_실패_index를_반환한다() {
        // given / when / then
        assertThatThrownBy(() -> consumer.onMessages(List.of(record(0, 11, 1), record(0, 22, 2)), mock(Consumer.class)))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, failure -> assertThat(failure.getIndex()).isEqualTo(1));
        verify(service).analyze(List.of(11L));
    }

    private ConsumerRecord<Object, Object> record(int partition, long id, int version) {
        return new ConsumerRecord<>("test", partition, id, "key",
                new ChatMessageCreatedEvent("event", version, id, 1L, Instant.now()));
    }
}
