package com.bobfull.chat.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 채팅방에 저장된 발신자와 메시지 원문을 보관한다.
@Entity
@Table(name = "chat_message", indexes = @Index(name = "idx_chat_message_room_id", columnList = "chat_room_id,chat_message_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_message_id")
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    @Column(name = "sender_member_id", nullable = false)
    private Long senderMemberId;

    @Column(name = "sender_participant_id", nullable = false)
    private Long senderParticipantId;

    @Column(nullable = false, length = 1000)
    private String content;

    private ChatMessage(Long roomId, Long memberId, Long participantId, String content) {
        validateContent(content);
        this.chatRoomId = roomId;
        this.senderMemberId = memberId;
        this.senderParticipantId = participantId;
        this.content = content;
    }

    public static ChatMessage create(Long roomId, Long memberId, Long participantId, String content) {
        return new ChatMessage(roomId, memberId, participantId, content);
    }

    private static void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > 1000) {
            throw new IllegalArgumentException("메시지 내용이 올바르지 않습니다.");
        }
    }
}
