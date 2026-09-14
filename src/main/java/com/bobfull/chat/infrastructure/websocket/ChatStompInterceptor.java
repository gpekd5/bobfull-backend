package com.bobfull.chat.infrastructure.websocket;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.application.port.ReservationChatAccessReader;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.auth.infrastructure.jwt.InvalidJwtException;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.member.domain.entity.MemberRole;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;

/** CONNECT 인증과 채팅방 SUBSCRIBE 인가만 처리하는 STOMP inbound interceptor다. */
@Component
public class ChatStompInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ChatStompInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern CHAT_ROOM_DESTINATION = Pattern.compile("^/sub/chat/rooms/(\\d+)$");
    private static final Pattern SEND_DESTINATION = Pattern.compile("^/pub/chat/rooms/[1-9]\\d*/messages$");
    private final JwtTokenProvider jwtTokenProvider;
    private final ChatRoomRepository chatRoomRepository;
    private final ReservationChatAccessReader reservationChatAccessReader;

    public ChatStompInterceptor(JwtTokenProvider jwtTokenProvider, ChatRoomRepository chatRoomRepository,
                                ReservationChatAccessReader reservationChatAccessReader) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.chatRoomRepository = chatRoomRepository;
        this.reservationChatAccessReader = reservationChatAccessReader;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) throw new StompAuthenticationException(StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) return authenticate(message, accessor);
        if (command == StompCommand.SUBSCRIBE) authorizeSubscribe(accessor);
        if (command == StompCommand.SEND) authorizeSend(accessor);
        return message;
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompPrincipal)) throw new StompAuthenticationException(StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        if (accessor.getDestination() == null || !SEND_DESTINATION.matcher(accessor.getDestination()).matches()) throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    private Message<?> authenticate(Message<?> message, StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null) throw new StompAuthenticationException(StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        if (!authorization.startsWith(BEARER_PREFIX) || authorization.length() == BEARER_PREFIX.length()) {
            throw new StompAuthenticationException(StompAuthenticationException.Reason.INVALID_BEARER_FORMAT);
        }
        AuthMember authMember;
        try { authMember = jwtTokenProvider.parseAccessToken(authorization.substring(BEARER_PREFIX.length())); }
        catch (InvalidJwtException exception) { throw new StompAuthenticationException(StompAuthenticationException.Reason.INVALID_TOKEN); }
        if (authMember.role() != MemberRole.MEMBER) throw new StompAuthenticationException(StompAuthenticationException.Reason.ROLE_NOT_ALLOWED);
        accessor.setUser(new StompPrincipal(authMember));
        log.info("CHAT_STOMP_CONNECTED memberId={} sessionId={}", authMember.id(), accessor.getSessionId());
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            throw new StompAuthenticationException(StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        }
        Matcher matcher = CHAT_ROOM_DESTINATION.matcher(accessor.getDestination());
        if (!matcher.matches()) throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        ChatRoom chatRoom = chatRoomRepository.findById(Long.parseLong(matcher.group(1)))
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        ReservationChatAccessReader.ChatAccess access = reservationChatAccessReader.read(
                chatRoom.getReservationId(), principal.authMember().id());
        if (access == null || !access.isActive()) throw new CustomException(CommonErrorCode.ACCESS_DENIED);
    }
}
