package com.bobfull.chat.infrastructure.repository;
import com.bobfull.chat.domain.entity.ChatMessage;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByChatRoomIdOrderByIdDesc(Long chatRoomId, Pageable pageable);
    List<ChatMessage> findByChatRoomIdAndIdLessThanOrderByIdDesc(Long chatRoomId, Long cursor, Pageable pageable);
    List<ChatMessage> findByChatRoomIdOrderByIdAsc(Long chatRoomId);
    List<ChatMessage> findTop20ByChatRoomIdAndCreatedAtLessThanEqualOrderByIdDesc(Long chatRoomId, Instant createdAt);

    @Query("""
            select message from ChatMessage message
            where message.chatRoomId = :chatRoomId
              and message.senderMemberId = :senderMemberId
              and message.createdAt >= :windowStart
              and (message.createdAt < :currentCreatedAt
                   or (message.createdAt = :currentCreatedAt and message.id <= :currentMessageId))
            order by message.createdAt desc, message.id desc
            """)
    List<ChatMessage> findRecentModerationContext(
            @Param("chatRoomId") Long chatRoomId,
            @Param("senderMemberId") Long senderMemberId,
            @Param("currentCreatedAt") Instant currentCreatedAt,
            @Param("currentMessageId") Long currentMessageId,
            @Param("windowStart") Instant windowStart,
            Pageable pageable);
}
