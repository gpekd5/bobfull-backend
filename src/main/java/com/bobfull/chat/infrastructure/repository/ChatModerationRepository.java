package com.bobfull.chat.infrastructure.repository;

import com.bobfull.chat.domain.entity.ChatModeration;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatModerationRepository extends JpaRepository<ChatModeration, Long> {
    Optional<ChatModeration> findByMessageId(Long messageId);
    List<ChatModeration> findByMessageIdIn(Collection<Long> messageIds);
}
