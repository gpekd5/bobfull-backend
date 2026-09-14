package com.bobfull.chat.application.service;
import com.bobfull.chat.presentation.dto.ChatRoomResponse;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.application.port.ReservationChatAccessReader;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.member.domain.entity.MemberRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ChatRoomQueryService {
    private static final Logger log = LoggerFactory.getLogger(ChatRoomQueryService.class);

    private final ChatRoomRepository rooms; private final ReservationChatAccessReader access;
    private final ChatRoomCreationService chatRoomCreationService;
    private final BusinessMetricRecorder businessMetricRecorder;
    public ChatRoomQueryService(ChatRoomRepository rooms, ReservationChatAccessReader access,
            ChatRoomCreationService chatRoomCreationService, BusinessMetricRecorder businessMetricRecorder) {
        this.rooms = rooms; this.access = access; this.chatRoomCreationService = chatRoomCreationService;
        this.businessMetricRecorder = businessMetricRecorder;
    }
    /**
     * 권한 검증 뒤 ChatRoom이 없으면 AFTER_COMMIT 생성이 아직 반영되지 않았거나 실패한
     * 것으로 보고 별도 트랜잭션(chatRoomCreationService, REQUIRES_NEW)에서 한 번 더
     * 멱등 생성을 시도한다. 이 read-only 트랜잭션 자체는 저장을 하지 않으며, 복구가 만든
     * 결과를 그대로 응답에 사용한다(같은 read-only 트랜잭션에서 다시 조회하면 격리 수준에
     * 따라 방금 커밋된 행을 못 볼 수 있어, 재조회 대신 복구 호출의 반환값을 그대로 쓴다).
     */
    @Transactional(readOnly = true) public ChatRoomResponse get(Long memberId, MemberRole role, Long reservationId) {
        if (role != MemberRole.MEMBER) throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        ReservationChatAccessReader.ChatAccess chatAccess = access.read(reservationId, memberId);
        if (chatAccess == null || !chatAccess.isActive()) throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        return ChatRoomResponse.from(rooms.findByReservationId(reservationId)
                .orElseGet(() -> recoverChatRoom(reservationId)));
    }

    private ChatRoom recoverChatRoom(Long reservationId) {
        try {
            return chatRoomCreationService.createIfAbsent(reservationId);
        } catch (RuntimeException exception) {
            log.error("event=CHAT_ROOM_CREATION_REQUIRED reservationId={} attemptSource=QUERY_RECOVERY autoRetry=false manualActionRequired=true",
                    reservationId, exception);
            businessMetricRecorder.increment(BusinessMetricEvent.CHAT_ROOM_CREATION_REQUIRED);
            throw new CustomException(ChatErrorCode.CHAT_ROOM_NOT_READY);
        }
    }
}
