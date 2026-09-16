package com.bobfull.chat.application.service;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 예약 확정 이후 채팅방을 멱등하게 생성하는 독립 트랜잭션을 제공한다.
@Service
@RequiredArgsConstructor
public class ChatRoomCreationService {

    private final ChatRoomRepository chatRoomRepository;

    // 핵심 예약 트랜잭션과 분리해 채팅방 생성 실패가 이미 확정된 예약을 되돌리지 않게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatRoom createIfAbsent(Long reservationId) {
        return chatRoomRepository.findByReservationId(reservationId)
                .orElseGet(() -> saveOrFindExisting(reservationId));
    }

    private ChatRoom saveOrFindExisting(Long reservationId) {
        try {
            return chatRoomRepository.save(ChatRoom.create(reservationId));
        } catch (DataIntegrityViolationException exception) {
            // 동시 생성 경쟁은 reservation_id UNIQUE를 최종 방어선으로 삼고 기존 결과를 반환한다.
            return chatRoomRepository.findByReservationId(reservationId).orElseThrow(() -> exception);
        }
    }
}
