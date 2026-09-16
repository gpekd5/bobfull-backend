package com.bobfull.chat.application.event;

import java.time.Instant;

// 개인정보 노출을 줄이기 위해 채팅 원문 없이 식별자만 Kafka로 전달한다.
public record ChatMessageCreatedEvent(
        String eventId,
        int eventVersion,
        Long messageId,
        Long chatRoomId,
        Instant occurredAt
) {
}
