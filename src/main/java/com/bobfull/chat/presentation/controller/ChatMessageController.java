package com.bobfull.chat.presentation.controller;

import com.bobfull.chat.application.service.ChatMessageCommandService;
import com.bobfull.chat.infrastructure.websocket.StompAuthenticationException;
import com.bobfull.chat.infrastructure.websocket.StompPrincipal;
import com.bobfull.chat.presentation.request.ChatMessageSendRequest;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

// 인증된 STOMP 발신자의 메시지 전송 요청을 Application 흐름으로 전달한다.
@Controller
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageCommandService service;

    @MessageMapping("/chat/rooms/{chatRoomId}/messages")
    public void send(
            @DestinationVariable Long chatRoomId,
            @Payload ChatMessageSendRequest request,
            Principal principal) {
        if (!(principal instanceof StompPrincipal stompPrincipal)) {
            throw new StompAuthenticationException(
                    StompAuthenticationException.Reason.MISSING_AUTHORIZATION);
        }
        service.send(chatRoomId, stompPrincipal.authMember(), request.content());
    }
}
