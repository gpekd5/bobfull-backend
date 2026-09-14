package com.bobfull.chat.application.event;

import java.time.Instant;

/** Kafka로 발행되는 최소 식별자 payload다. 채팅 원문은 절대 포함하지 않는다. */
public record ChatMessageCreatedEvent(
        String eventId,
        int eventVersion,
        Long messageId,
        Long chatRoomId,
        Instant occurredAt
) {
}
