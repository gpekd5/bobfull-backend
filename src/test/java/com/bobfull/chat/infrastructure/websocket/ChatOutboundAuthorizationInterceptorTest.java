package com.bobfull.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.application.port.ReservationChatAccessReader;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

class ChatOutboundAuthorizationInterceptorTest {

    private final ChatRoomRepository rooms = org.mockito.Mockito.mock(ChatRoomRepository.class);
    private final ReservationChatAccessReader access = org.mockito.Mockito.mock(ReservationChatAccessReader.class);
    private final SimpUserRegistry registry = org.mockito.Mockito.mock(SimpUserRegistry.class);
    private final ChatOutboundAuthorizationInterceptor interceptor =
            new ChatOutboundAuthorizationInterceptor(rooms, access, registry);

    @Test
    void 활성_참여자에게는_구독_메시지가_그대로_전달된다() {
        // given
        given(rooms.findById(3L)).willReturn(Optional.of(room(3L, 10L)));
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessReader.ChatAccess(4L, ParticipationStatus.RESERVED));
        registerSession("session-1", 7L);

        // when
        Message<?> result = interceptor.preSend(outboundMessage("session-1", "/sub/chat/rooms/3"), null);

        // then
        assertThat(result).isNotNull();
    }

    @Test
    void SUBSCRIBE_이후_최종_CANCELLED로_확정된_참여자에게는_새_메시지가_전달되지_않는다() {
        // given
        given(rooms.findById(3L)).willReturn(Optional.of(room(3L, 10L)));
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessReader.ChatAccess(4L, ParticipationStatus.CANCELLED));
        registerSession("session-1", 7L);

        // when
        Message<?> result = interceptor.preSend(outboundMessage("session-1", "/sub/chat/rooms/3"), null);

        // then
        assertThat(result).isNull();
    }

    @Test
    void 채팅방_구독_경로가_아니면_그대로_통과한다() {
        // when
        Message<?> result = interceptor.preSend(outboundMessage("session-1", "/sub/other"), null);

        // then
        assertThat(result).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(rooms, access);
    }

    @Test
    void HEARTBEAT_등_MESSAGE가_아닌_프레임은_그대로_통과한다() {
        // given
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT);
        accessor.setSessionId("session-1");
        accessor.setLeaveMutable(true);
        Message<byte[]> heartbeat = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // when
        Message<?> result = interceptor.preSend(heartbeat, null);

        // then
        assertThat(result).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(rooms, access);
    }

    @Test
    void 등록된_세션을_찾을수_없으면_차단한다() {
        // given: registry에 어떤 세션도 없음(예: 연결이 이미 끊긴 상태)
        given(registry.getUsers()).willReturn(Set.of());

        // when
        Message<?> result = interceptor.preSend(outboundMessage("unknown-session", "/sub/chat/rooms/3"), null);

        // then
        assertThat(result).isNull();
    }

    @Test
    void 존재하지_않는_채팅방이면_차단한다() {
        // given
        given(rooms.findById(99L)).willReturn(Optional.empty());
        registerSession("session-1", 7L);

        // when
        Message<?> result = interceptor.preSend(outboundMessage("session-1", "/sub/chat/rooms/99"), null);

        // then
        assertThat(result).isNull();
    }

    private void registerSession(String sessionId, Long memberId) {
        SimpSession session = org.mockito.Mockito.mock(SimpSession.class);
        SimpUser user = org.mockito.Mockito.mock(SimpUser.class);
        given(user.getName()).willReturn(memberId.toString());
        given(user.getSession(sessionId)).willReturn(session);
        given(registry.getUsers()).willReturn(Set.of(user));
    }

    private Message<byte[]> outboundMessage(String sessionId, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private ChatRoom room(Long id, Long reservationId) {
        ChatRoom room = ChatRoom.create(reservationId);
        ReflectionTestUtils.setField(room, "id", id);
        return room;
    }
}
