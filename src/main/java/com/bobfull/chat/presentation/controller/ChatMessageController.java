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
