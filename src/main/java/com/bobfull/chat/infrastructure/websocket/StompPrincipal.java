package com.bobfull.chat.infrastructure.websocket;

import com.bobfull.auth.application.model.AuthMember;
import java.security.Principal;

// CONNECT에서 검증한 인증 정보를 STOMP 세션에 전달한다.
public record StompPrincipal(AuthMember authMember) implements Principal {
    @Override
    public String getName() {
        return authMember.id().toString();
    }
}
