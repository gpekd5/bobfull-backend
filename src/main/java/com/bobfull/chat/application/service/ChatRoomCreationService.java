package com.bobfull.chat.application.service;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChatRoom 생성만 담당하는 독립된 짧은 트랜잭션이다. 결제·예약 확정의 핵심 트랜잭션이 이미
 * 커밋된 뒤(AFTER_COMMIT)에만 호출되도록 설계됐으므로, REQUIRES_NEW로 완전히 새 트랜잭션을
 * 열어 이 저장이 실패해도 호출자 쪽에 되돌릴 활성 트랜잭션이 없게 한다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomCreationService {

    private final ChatRoomRepository chatRoomRepository;

    /**
     * reservationId 기준으로 멱등하게 생성한다. 조회 이후 저장 사이에 동시 요청이 들어올 수
     * 있으므로, 최종 방어는 {@code chat_room.reservation_id} UNIQUE 제약에 맡기고, 그
     * 제약을 위반한 경우에만 다시 조회해 다른 트랜잭션이 만든 ChatRoom을 정상 결과로 반환한다.
     * 재조회에도 없다면 UNIQUE 위반이 아닌 다른 오류이므로 그대로 전파한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatRoom createIfAbsent(Long reservationId) {
        return chatRoomRepository.findByReservationId(reservationId)
                .orElseGet(() -> saveOrFindExisting(reservationId));
    }

    private ChatRoom saveOrFindExisting(Long reservationId) {
        try {
            return chatRoomRepository.save(ChatRoom.create(reservationId));
        } catch (DataIntegrityViolationException exception) {
            return chatRoomRepository.findByReservationId(reservationId).orElseThrow(() -> exception);
        }
    }
}
