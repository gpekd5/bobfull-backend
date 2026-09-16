package com.bobfull.chat.infrastructure.repository;

import com.bobfull.chat.domain.entity.ChatRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByReservationId(Long reservationId);
}
