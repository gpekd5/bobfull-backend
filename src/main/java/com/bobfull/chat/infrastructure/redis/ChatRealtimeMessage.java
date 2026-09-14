package com.bobfull.chat.infrastructure.redis;

import com.bobfull.chat.presentation.dto.ChatMessageSentResponse;
import java.time.Instant;

/** Redis Pub/Sub으로 인스턴스 간에만 전달하는 채팅 실시간 전파 payload다. */
public record ChatRealtimeMessage(
        Long messageId, Long chatRoomId, Long senderMemberId, Long senderParticipantId,
        String senderName, String content, Instant sentAt
) {
    public static ChatRealtimeMessage from(ChatMessageSentResponse response) {
        return new ChatRealtimeMessage(response.messageId(), response.chatRoomId(), response.senderMemberId(),
                response.senderParticipantId(), response.senderName(), response.content(), response.sentAt());
    }
}
