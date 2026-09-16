package com.bobfull.chat.infrastructure.websocket;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

// 브로커가 메시지를 전달하기 직전에 구독자의 최신 예약 참여 권한을 다시 검증한다.
@Component
public class ChatOutboundAuthorizationInterceptor implements ChannelInterceptor {
    private static final Pattern CHAT_ROOM_DESTINATION = Pattern.compile("^/sub/chat/rooms/(\\d+)$");

    private final ChatRoomRepository chatRoomRepository;
    private final ReservationChatAccessPort reservationChatAccessReader;
    private final SimpUserRegistry simpUserRegistry;

    // @Lazy: SimpUserRegistry는 모든 WebSocketMessageBrokerConfigurer(WebSocketConfig 포함)를 먼저
    // 처리해야 만들어지는데, WebSocketConfig가 이 인터셉터를 생성자에서 주입받으므로 즉시 주입하면
    // WebSocketConfig 생성 중에 자기 자신을 다시 필요로 하는 순환 참조가 된다.
    public ChatOutboundAuthorizationInterceptor(
            ChatRoomRepository chatRoomRepository,
            ReservationChatAccessPort reservationChatAccessReader,
            @Lazy SimpUserRegistry simpUserRegistry
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.reservationChatAccessReader = reservationChatAccessReader;
        this.simpUserRegistry = simpUserRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        if (accessor.getMessageType() != SimpMessageType.MESSAGE) {
            return message;
        }

        String destination = accessor.getDestination();
        Matcher matcher = destination == null ? null : CHAT_ROOM_DESTINATION.matcher(destination);
        if (matcher == null || !matcher.matches()) {
            return message;
        }

        Long memberId = resolveMemberId(accessor.getSessionId());
        if (memberId == null) {
            return null;
        }

        ChatRoom chatRoom = chatRoomRepository.findById(Long.parseLong(matcher.group(1))).orElse(null);
        if (chatRoom == null) {
            return null;
        }

        // 구독 뒤 참여가 취소돼도 새 메시지가 전달되지 않도록 outbound마다 현재 상태를 조회한다.
        ReservationChatAccessPort.ChatAccess access =
                reservationChatAccessReader.read(chatRoom.getReservationId(), memberId);
        return (access != null && access.isActive()) ? message : null;
    }

    private Long resolveMemberId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        for (SimpUser user : simpUserRegistry.getUsers()) {
            if (user.getSession(sessionId) != null) {
                return Long.valueOf(user.getName());
            }
        }
        return null;
    }
}
