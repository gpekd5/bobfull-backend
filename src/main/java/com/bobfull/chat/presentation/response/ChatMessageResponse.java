package com.bobfull.chat.presentation.response;

import com.bobfull.chat.domain.entity.ChatMessage;
import java.time.OffsetDateTime;

public record ChatMessageResponse(
        Long messageId,
        Long senderMemberId,
        String senderName,
        String content,
        OffsetDateTime sentAt
) {
    public static ChatMessageResponse of(ChatMessage message, String name, OffsetDateTime sentAt) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSenderMemberId(),
                name,
                message.getContent(),
                sentAt
        );
    }
}
