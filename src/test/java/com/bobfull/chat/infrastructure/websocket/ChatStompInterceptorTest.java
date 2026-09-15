package com.bobfull.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.member.domain.entity.MemberRole;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class ChatStompInterceptorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
    private final JwtTokenProvider tokens = new JwtTokenProvider(clock, "chat-stomp-test-secret-key-please-keep-this-long", 3600);
    private final ChatRoomRepository rooms = org.mockito.Mockito.mock(ChatRoomRepository.class);
    private final ReservationChatAccessPort access = org.mockito.Mockito.mock(ReservationChatAccessPort.class);
    private final ChatStompInterceptor interceptor = new ChatStompInterceptor(tokens, rooms, access);

    @Test
    void MEMBER_CONNECT는_JWT_memberId를_Principal에_등록한다() {
        Message<?> result = interceptor.preSend(connect("Bearer " + tokens.createAccessToken(7L, MemberRole.MEMBER)), null);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isInstanceOf(StompPrincipal.class);
        assertThat(((StompPrincipal) accessor.getUser()).authMember().id()).isEqualTo(7L);
    }

    @Test
    void CONNECT_인증_성공시_원본_accessor의_userChangeCallback으로_Principal이_전달되어_SUBSCRIBE_SEND에서_그대로_읽힌다() {
        // given: StompSubProtocolHandler가 실제로 하듯 원본 accessor에 callback을 등록해둔 CONNECT 메시지를 만든다
        StompHeaderAccessor original = StompHeaderAccessor.create(StompCommand.CONNECT);
        original.setLeaveMutable(true);
        original.setNativeHeader("Authorization", "Bearer " + tokens.createAccessToken(7L, MemberRole.MEMBER));
        AtomicReference<Principal> captured = new AtomicReference<>();
        original.setUserChangeCallback(captured::set);
        Message<?> connectMessage = MessageBuilder.createMessage(new byte[0], original.getMessageHeaders());

        // when
        interceptor.preSend(connectMessage, null);

        // then: callback이 실행되어 JWT의 memberId(7)로 만든 StompPrincipal을 감지했다
        assertThat(captured.get()).isInstanceOf(StompPrincipal.class);
        assertThat(((StompPrincipal) captured.get()).authMember().id()).isEqualTo(7L);

        // then: interceptor는 새 accessor를 만들지 않고 원본 accessor를 그대로 사용했다
        StompHeaderAccessor sameAccessor = MessageHeaderAccessor.getAccessor(connectMessage, StompHeaderAccessor.class);
        assertThat(sameAccessor).isSameAs(original);
        assertThat(sameAccessor.getUser()).isEqualTo(captured.get());

        // then: 세션이 채워준 Principal을 SUBSCRIBE에서 수동 재주입 없이 그대로 읽을 수 있는 구조다
        given(rooms.findById(3L)).willReturn(Optional.of(room(3L, 10L)));
        given(access.read(10L, 7L)).willReturn(
                new ReservationChatAccessPort.ChatAccess(4L, com.bobfull.reservation.domain.entity.ParticipationStatus.RESERVED));
        StompHeaderAccessor subscribeAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribeAccessor.setDestination("/sub/chat/rooms/3");
        subscribeAccessor.setUser(sameAccessor.getUser());
        Message<?> subscribeMessage = MessageBuilder.createMessage(new byte[0], subscribeAccessor.getMessageHeaders());

        assertThatCode(() -> interceptor.preSend(subscribeMessage, null)).doesNotThrowAnyException();
    }

    @Test
    void 원본_accessor를_찾을수_없는_메시지는_인증_예외로_차단한다() {
        Message<?> withoutMutableAccessor = MessageBuilder.withPayload(new byte[0]).build();
        assertReason(() -> interceptor.preSend(withoutMutableAccessor, null), StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
    }

    @Test
    void 헤더_없음_Bearer_형식오류_위조만료토큰과_비_MEMBER는_CONNECT를_차단한다() {
        assertReason(() -> interceptor.preSend(connect(null), null), StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        assertReason(() -> interceptor.preSend(connect("Token value"), null), StompAuthenticationException.Reason.INVALID_BEARER_FORMAT);
        assertReason(() -> interceptor.preSend(connect("Bearer forged"), null), StompAuthenticationException.Reason.INVALID_TOKEN);
        JwtTokenProvider expired = new JwtTokenProvider(Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC), "chat-stomp-test-secret-key-please-keep-this-long", -1);
        assertReason(() -> interceptor.preSend(connect("Bearer " + expired.createAccessToken(7L, MemberRole.MEMBER)), null), StompAuthenticationException.Reason.INVALID_TOKEN);
        assertReason(() -> interceptor.preSend(connect("Bearer " + tokens.createAccessToken(7L, MemberRole.OWNER)), null), StompAuthenticationException.Reason.ROLE_NOT_ALLOWED);
    }

    @Test
    void 유효_참여자와_CANCEL_REQUESTED와_NO_SHOW는_SUBSCRIBE할수있다() {
        ChatRoom room = room(3L, 10L);
        given(rooms.findById(3L)).willReturn(Optional.of(room));
        allow(ParticipationStatusCase.RESERVED); interceptor.preSend(subscribe(7L, "/sub/chat/rooms/3"), null);
        allow(ParticipationStatusCase.CANCEL_REQUESTED); interceptor.preSend(subscribe(7L, "/sub/chat/rooms/3"), null);
        allow(ParticipationStatusCase.NO_SHOW); interceptor.preSend(subscribe(7L, "/sub/chat/rooms/3"), null);
    }

    @Test
    void 인증없음_비참여자_CANCELLED와_잘못된_destination은_SUBSCRIBE를_차단한다() {
        assertReason(() -> interceptor.preSend(subscribeWithoutPrincipal("/sub/chat/rooms/3"), null), StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        given(rooms.findById(3L)).willReturn(Optional.of(room(3L, 10L)));
        given(access.read(10L, 7L)).willReturn(null);
        assertAccessDenied(() -> interceptor.preSend(subscribe(7L, "/sub/chat/rooms/3"), null));
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessPort.ChatAccess(4L, com.bobfull.reservation.domain.entity.ParticipationStatus.CANCELLED));
        assertAccessDenied(() -> interceptor.preSend(subscribe(7L, "/sub/chat/rooms/3"), null));
        given(rooms.findById(4L)).willReturn(Optional.of(room(4L, 20L)));
        given(access.read(20L, 7L)).willReturn(null);
        assertAccessDenied(() -> interceptor.preSend(subscribe(7L, "/sub/chat/rooms/4"), null));
        assertThatThrownBy(() -> interceptor.preSend(subscribe(7L, "/sub/other/3"), null)).isInstanceOf(CustomException.class);
    }

    @Test
    void SEND는_Principal과_정확한_pub_destination만_허용한다() {
        interceptor.preSend(send(7L, "/pub/chat/rooms/3/messages"), null);
        assertReason(() -> interceptor.preSend(sendWithoutPrincipal("/pub/chat/rooms/3/messages"), null), StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        assertThatThrownBy(() -> interceptor.preSend(send(7L, "/pub/chat/rooms/0/messages"), null)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> interceptor.preSend(send(7L, "/pub/other"), null)).isInstanceOf(CustomException.class);
    }

    private void allow(ParticipationStatusCase status) { given(access.read(10L, 7L)).willReturn(new ReservationChatAccessPort.ChatAccess(4L, status.value)); }
    private Message<?> connect(String header) { StompHeaderAccessor a=StompHeaderAccessor.create(StompCommand.CONNECT); a.setLeaveMutable(true); if(header!=null)a.setNativeHeader("Authorization",header); return MessageBuilder.createMessage(new byte[0],a.getMessageHeaders()); }
    private Message<?> subscribe(Long memberId, String destination) { StompHeaderAccessor a=StompHeaderAccessor.create(StompCommand.SUBSCRIBE); a.setDestination(destination); a.setUser(new StompPrincipal(new com.bobfull.auth.application.model.AuthMember(memberId, MemberRole.MEMBER))); return MessageBuilder.createMessage(new byte[0],a.getMessageHeaders()); }
    private Message<?> subscribeWithoutPrincipal(String destination) { StompHeaderAccessor a=StompHeaderAccessor.create(StompCommand.SUBSCRIBE); a.setDestination(destination); return MessageBuilder.createMessage(new byte[0],a.getMessageHeaders()); }
    private Message<?> send(Long memberId, String destination) { StompHeaderAccessor a=StompHeaderAccessor.create(StompCommand.SEND); a.setDestination(destination); a.setUser(new StompPrincipal(new com.bobfull.auth.application.model.AuthMember(memberId, MemberRole.MEMBER))); return MessageBuilder.createMessage(new byte[0],a.getMessageHeaders()); }
    private Message<?> sendWithoutPrincipal(String destination) { StompHeaderAccessor a=StompHeaderAccessor.create(StompCommand.SEND); a.setDestination(destination); return MessageBuilder.createMessage(new byte[0],a.getMessageHeaders()); }
    private ChatRoom room(Long id, Long reservationId) { ChatRoom room=ChatRoom.create(reservationId); ReflectionTestUtils.setField(room,"id",id); return room; }
    private void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, StompAuthenticationException.Reason reason) { assertThatThrownBy(action).isInstanceOf(StompAuthenticationException.class).extracting(e -> ((StompAuthenticationException)e).getReason()).isEqualTo(reason); }
    private void assertAccessDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOf(CustomException.class).extracting(e -> ((CustomException)e).getErrorCode()).isEqualTo(CommonErrorCode.ACCESS_DENIED); }
    private enum ParticipationStatusCase { RESERVED(com.bobfull.reservation.domain.entity.ParticipationStatus.RESERVED), CANCEL_REQUESTED(com.bobfull.reservation.domain.entity.ParticipationStatus.CANCEL_REQUESTED), NO_SHOW(com.bobfull.reservation.domain.entity.ParticipationStatus.NO_SHOW); final com.bobfull.reservation.domain.entity.ParticipationStatus value; ParticipationStatusCase(com.bobfull.reservation.domain.entity.ParticipationStatus value){this.value=value;} }
}
