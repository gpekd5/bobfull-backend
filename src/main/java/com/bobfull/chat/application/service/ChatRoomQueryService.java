package com.bobfull.chat.application.service;

import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.presentation.response.ChatRoomResponse;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.member.domain.entity.MemberRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 예약 참여 권한을 검증하고 누락된 채팅방을 멱등하게 복구해 조회한다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomQueryService {

    private final ChatRoomRepository rooms;
    private final ReservationChatAccessPort access;
    private final ChatRoomCreationService chatRoomCreationService;
    private final BusinessMetricRecorder businessMetricRecorder;

    // 권한 확인 후 누락된 채팅방을 별도 트랜잭션에서 복구해 같은 요청에 반환한다.
    public ChatRoomResponse get(Long memberId, MemberRole role, Long reservationId) {
        if (role != MemberRole.MEMBER) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }

        ReservationChatAccessPort.ChatAccess chatAccess = access.read(reservationId, memberId);
        if (chatAccess == null || !chatAccess.isActive()) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }

        // read-only 트랜잭션의 기존 snapshot을 다시 읽지 않고 복구 트랜잭션의 반환값을 사용한다.
        return ChatRoomResponse.from(rooms.findByReservationId(reservationId)
                .orElseGet(() -> recoverChatRoom(reservationId)));
    }

    private ChatRoom recoverChatRoom(Long reservationId) {
        try {
            return chatRoomCreationService.createIfAbsent(reservationId);
        } catch (RuntimeException exception) {
            log.error(
                    "event=CHAT_ROOM_CREATION_REQUIRED reservationId={} attemptSource=QUERY_RECOVERY autoRetry=false manualActionRequired=true",
                    reservationId,
                    exception);
            businessMetricRecorder.increment(BusinessMetricEvent.CHAT_ROOM_CREATION_REQUIRED);
            throw new CustomException(ChatErrorCode.CHAT_ROOM_NOT_READY);
        }
    }
}
