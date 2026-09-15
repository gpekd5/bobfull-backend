package com.bobfull.chat.presentation.response;

import com.bobfull.chat.domain.entity.ChatMessage;
import java.time.Instant;

public record ChatMessageSentResponse(
        Long messageId,
        Long chatRoomId,
        Long senderMemberId,
        Long senderParticipantId,
        String senderName,
        String content,
        Instant sentAt
) {
    public static ChatMessageSentResponse of(ChatMessage message, String name) {
        return new ChatMessageSentResponse(
                message.getId(),
                message.getChatRoomId(),
                message.getSenderMemberId(),
                message.getSenderParticipantId(),
                name,
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
