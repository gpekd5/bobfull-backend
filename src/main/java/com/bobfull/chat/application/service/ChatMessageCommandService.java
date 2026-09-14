package com.bobfull.chat.application.service;
import com.bobfull.chat.presentation.dto.ChatMessageSentResponse;
import com.bobfull.chat.infrastructure.redis.RedisChatMessagePublisher;
import com.bobfull.chat.domain.entity.*;
import com.bobfull.chat.application.port.*;
import com.bobfull.chat.infrastructure.repository.*;
import com.bobfull.common.exception.*;
import com.bobfull.auth.application.model.AuthMember; import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.common.outbox.entity.OutboxEvent;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.chat.infrastructure.outbox.ChatMessageOutboxSignalDispatcher;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class ChatMessageCommandService {
    private final ChatRoomRepository rooms; private final ChatMessageRepository messages; private final ReservationChatAccessReader access; private final MemberNameReader names; private final Clock clock;
    private final OutboxEventRepository outboxEvents; private final ChatMessageOutboxSignalDispatcher outboxSignalDispatcher; private final RedisChatMessagePublisher realtimePublisher;
    /** #192 Kafka vs Async Baseline 비교 전용. 기본값(Bean 없음)에서는 항상 null이라 기존 Outbox/Kafka 경로는 바뀌지 않는다. */
    @Autowired(required = false)
    private ChatMessageAsyncModerationDispatcher asyncModerationDispatcher;
    public ChatMessageCommandService(ChatRoomRepository rooms, ChatMessageRepository messages, ReservationChatAccessReader access, MemberNameReader names, Clock clock,
            OutboxEventRepository outboxEvents, ChatMessageOutboxSignalDispatcher outboxSignalDispatcher, RedisChatMessagePublisher realtimePublisher) {
        this.rooms=rooms;this.messages=messages;this.access=access;this.names=names;this.clock=clock;this.outboxEvents=outboxEvents;this.outboxSignalDispatcher=outboxSignalDispatcher;this.realtimePublisher=realtimePublisher;
    }
    @Transactional public ChatMessageSentResponse send(Long roomId, AuthMember member, String content) {
        if(member.role()!=MemberRole.MEMBER) throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        if(content==null||content.isBlank()||content.length()>1000) throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        ChatRoom room=rooms.findById(roomId).orElseThrow(()->new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        ReservationChatAccessReader.ChatAccess current=access.read(room.getReservationId(),member.id());
        if(current==null||!current.isActive()) throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        if(!current.canSend(clock.instant())) throw new CustomException(ChatErrorCode.CHAT_MESSAGE_SEND_NOT_ALLOWED);
        ChatMessage saved=messages.save(ChatMessage.create(roomId,member.id(),current.participantId(),content));
        OutboxEvent outboxEvent=outboxEvents.save(OutboxEvent.chatMessageCreated(saved.getId(),clock.instant()));
        Map<Long,String> namesById=names.readNames(java.util.Set.of(member.id()));
        ChatMessageSentResponse response=ChatMessageSentResponse.of(saved,namesById.get(member.id()));
        AfterCommitExecutor.run(()->outboxSignalDispatcher.dispatch(outboxEvent.getId()));
        AfterCommitExecutor.run(()->realtimePublisher.publish(response));
        if (asyncModerationDispatcher != null) {
            AfterCommitExecutor.run(()->asyncModerationDispatcher.dispatch(saved.getId()));
        }
        return response;
    }
}
