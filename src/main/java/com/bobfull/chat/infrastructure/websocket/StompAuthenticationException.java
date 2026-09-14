package com.bobfull.chat.infrastructure.websocket;

/** STOMP CONNECT 인증 실패 원인을 token 원문 없이 구분한다. */
public class StompAuthenticationException extends RuntimeException {
    public enum Reason { MISSING_AUTHORIZATION, INVALID_BEARER_FORMAT, INVALID_TOKEN, ROLE_NOT_ALLOWED }
    private final Reason reason;
    public StompAuthenticationException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason getReason() { return reason; }
}
