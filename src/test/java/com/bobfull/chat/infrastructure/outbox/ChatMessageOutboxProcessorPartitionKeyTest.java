package com.bobfull.chat.infrastructure.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.outbox.entity.OutboxEvent;
import com.bobfull.outbox.repository.OutboxEventRepository;
import com.bobfull.outbox.service.OutboxEventTransactionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #192 실험: {@code bobfull.kafka.chat-message.partition-key-strategy}가 실제 발행 key를
 * 바꾸는지 검증한다. 이 단위 테스트는 생성자에 주입된 전략의 발행 key만 검증한다.
 */
class ChatMessageOutboxProcessorPartitionKeyTest {

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final OutboxEventTransactionService transactionService = mock(OutboxEventTransactionService.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final KafkaOperations<Object, Object> kafkaTemplate = mock(KafkaOperations.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void message_id_전략을_설정하면_messageId를_key로_사용한다() {
        ChatMessageOutboxProcessor processor = new ChatMessageOutboxProcessor(
                outboxEventRepository, transactionService, chatMessageRepository, kafkaTemplate, clock,
                "bobfull.chat.message-created.v1", 10L, "message-id");
        prepare(processor, 502L, 42L, 7L);

        processor.process(502L);

        verify(kafkaTemplate).send(eq("bobfull.chat.message-created.v1"), eq("7"), any());
    }

    @Test
    void chat_room_전략을_설정하면_chatRoomId를_key로_사용한다() {
        ChatMessageOutboxProcessor processor = new ChatMessageOutboxProcessor(
                outboxEventRepository, transactionService, chatMessageRepository, kafkaTemplate, clock,
                "bobfull.chat.message-created.v1", 10L, "chat-room");
        prepare(processor, 503L, 42L, 9L);

        processor.process(503L);

        verify(kafkaTemplate).send(eq("bobfull.chat.message-created.v1"), eq("42"), any());
    }

    private void prepare(ChatMessageOutboxProcessor processor, Long eventId, Long roomId, Long messageId) {
        ChatMessage message = ChatMessage.create(roomId, 1L, 1L, "테스트 메시지");
        ReflectionTestUtils.setField(message, "id", messageId);
        OutboxEvent outboxEvent = OutboxEvent.chatMessageCreated(messageId, clock.instant());
        ReflectionTestUtils.setField(outboxEvent, "id", eventId);

        OutboxEventTransactionService.ClaimedOutboxEvent claimed =
                new OutboxEventTransactionService.ClaimedOutboxEvent(eventId, "CHAT_MESSAGE_CREATED", messageId, 0, "token");
        given(transactionService.claim(eq(eventId), any(), any())).willReturn(Optional.of(claimed));
        given(transactionService.complete(eq(claimed), any())).willReturn(true);
        given(chatMessageRepository.findById(messageId)).willReturn(Optional.of(message));
        given(outboxEventRepository.findById(eventId)).willReturn(Optional.of(outboxEvent));
        given(kafkaTemplate.send(any(String.class), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
    }
}
