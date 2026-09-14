package com.bobfull.chat.presentation.dto;
import com.bobfull.chat.domain.entity.ChatMessage;
import java.time.Instant;
public record ChatMessageSentResponse(Long messageId, Long chatRoomId, Long senderMemberId, Long senderParticipantId, String senderName, String content, Instant sentAt) { public static ChatMessageSentResponse of(ChatMessage m, String name) { return new ChatMessageSentResponse(m.getId(),m.getChatRoomId(),m.getSenderMemberId(),m.getSenderParticipantId(),name,m.getContent(),m.getCreatedAt()); } }
