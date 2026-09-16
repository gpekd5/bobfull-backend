package com.bobfull.chat.application.service;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.chat.application.port.MemberNamePort;
import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.infrastructure.outbox.ChatMessageOutboxSignalDispatcher;
import com.bobfull.chat.infrastructure.redis.RedisChatMessagePublisher;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.presentation.response.ChatMessageSentResponse;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.outbox.entity.OutboxEvent;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.member.domain.entity.MemberRole;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 채팅 참여 권한을 검증해 메시지와 후속 분석 Outbox를 저장하고 실시간 전파를 예약한다.
@Service
@RequiredArgsConstructor
@Transactional
public class ChatMessageCommandService {

    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ReservationChatAccessPort access;
    private final MemberNamePort names;
    private final Clock clock;
    private final OutboxEventRepository outboxEvents;
    private final ChatMessageOutboxSignalDispatcher outboxSignalDispatcher;
    private final RedisChatMessagePublisher realtimePublisher;

    // 비동기 Baseline 측정이 명시적으로 활성화된 경우에만 추가 분석 경로를 사용한다.
    @Autowired(required = false)
    private ChatMessageAsyncModerationDispatcher asyncModerationDispatcher;

    // 메시지와 분석 Outbox를 함께 저장하고 커밋된 메시지만 외부 채널로 전달한다.
    public ChatMessageSentResponse send(Long roomId, AuthMember member, String content) {
        if (member.role() != MemberRole.MEMBER) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
        if (content == null || content.isBlank() || content.length() > 1000) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }

        ChatRoom room = rooms.findById(roomId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        ReservationChatAccessPort.ChatAccess current = access.read(room.getReservationId(), member.id());
        if (current == null || !current.isActive()) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
        if (!current.canSend(clock.instant())) {
            throw new CustomException(ChatErrorCode.CHAT_MESSAGE_SEND_NOT_ALLOWED);
        }

        // 메시지 저장과 분석 의도를 같은 트랜잭션에 묶어 유실되지 않게 한다.
        ChatMessage saved = messages.save(
                ChatMessage.create(roomId, member.id(), current.participantId(), content));
        OutboxEvent outboxEvent = outboxEvents.save(
                OutboxEvent.chatMessageCreated(saved.getId(), clock.instant()));
        Map<Long, String> namesById = names.readNames(Set.of(member.id()));
        ChatMessageSentResponse response = ChatMessageSentResponse.of(
                saved, namesById.get(member.id()));

        // 롤백된 메시지가 Kafka나 Redis로 노출되지 않도록 커밋 이후에만 후속 처리를 시작한다.
        AfterCommitExecutor.run(() -> outboxSignalDispatcher.dispatch(outboxEvent.getId()));
        AfterCommitExecutor.run(() -> realtimePublisher.publish(response));
        if (asyncModerationDispatcher != null) {
            AfterCommitExecutor.run(() -> asyncModerationDispatcher.dispatch(saved.getId()));
        }
        return response;
    }
}
