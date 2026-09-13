package com.bobfull.auth.infrastructure.jwt;

/**
 * Access Token 형식·서명·만료 검증에 실패했을 때 발생한다.
 * JwtAuthenticationFilter가 이 예외를 잡아 인증 미설정으로 처리하면 401로 이어진다.
 */
public class InvalidJwtException extends RuntimeException {

    public InvalidJwtException(String message) {
        super(message);
    }

    public InvalidJwtException(String message, Throwable cause) {
        super(message, cause);
    }
}
