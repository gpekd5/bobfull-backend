package com.bobfull.chat.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class ChatMessageRepositoryTest {
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void 같은_예약에는_채팅방을_하나만_저장한다() {
        // given
        chatRoomRepository.saveAndFlush(ChatRoom.create(10L));

        // when & then
        assertThatThrownBy(() -> chatRoomRepository.saveAndFlush(ChatRoom.create(10L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cursor보다_작은_같은_채팅방_메시지만_최신순으로_조회한다() {
        // given
        ChatRoom first = chatRoomRepository.save(ChatRoom.create(10L));
        ChatRoom other = chatRoomRepository.save(ChatRoom.create(20L));
        ChatMessage oldest = chatMessageRepository.save(ChatMessage.create(first.getId(), 1L, 1L, "첫 메시지"));
        ChatMessage cursor = chatMessageRepository.save(ChatMessage.create(first.getId(), 1L, 1L, "두 번째 메시지"));
        chatMessageRepository.save(ChatMessage.create(first.getId(), 1L, 1L, "세 번째 메시지"));
        chatMessageRepository.save(ChatMessage.create(other.getId(), 2L, 2L, "다른 방 메시지"));

        // when
        List<ChatMessage> result = chatMessageRepository.findByChatRoomIdAndIdLessThanOrderByIdDesc(
                first.getId(), cursor.getId(), PageRequest.of(0, 3));

        // then
        assertThat(result).extracting(ChatMessage::getId).containsExactly(oldest.getId());
    }

    @Test
    void moderation_context는_sameRoom_sameSender_과거_범위만_createdAt_id_순으로_조회한다() {
        ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(30L));
        Instant base = Instant.parse("2026-08-14T00:00:00Z");
        ChatMessage oldest = savedMessage(room.getId(), 10L, base.minusSeconds(2), "시");
        ChatMessage sameTimePast = savedMessage(room.getId(), 10L, base, "발");
        ChatMessage current = savedMessage(room.getId(), 10L, base, "아");
        savedMessage(room.getId(), 10L, base.plusSeconds(1), "미래");
        savedMessage(room.getId(), 11L, base.minusSeconds(1), "다른 sender");

        List<ChatMessage> result = chatMessageRepository.findRecentModerationContext(room.getId(), 10L, base, current.getId(),
                base.minusSeconds(30), PageRequest.of(0, 5));

        assertThat(result).extracting(ChatMessage::getId).containsExactly(current.getId(), sameTimePast.getId(), oldest.getId());
    }

    private ChatMessage savedMessage(Long roomId, Long senderId, Instant createdAt, String content) {
        ChatMessage message = chatMessageRepository.saveAndFlush(ChatMessage.create(roomId, senderId, senderId, content));
        entityManager.createNativeQuery("update chat_message set created_at = ? where chat_message_id = ?")
                .setParameter(1, createdAt).setParameter(2, message.getId()).executeUpdate();
        entityManager.clear();
        return chatMessageRepository.findById(message.getId()).orElseThrow();
    }
}
