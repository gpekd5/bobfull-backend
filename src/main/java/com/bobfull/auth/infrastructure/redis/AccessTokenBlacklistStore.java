package com.bobfull.auth.infrastructure.redis;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그아웃된 Access Token의 jti를 Redis에 등록·조회한다(Issue #186). TTL을 남은 유효시간으로 두어
 * JWT 자체가 만료되면 Blacklist 항목도 같은 시점에 자동으로 사라진다. Redis 조회 실패(연결 장애 등)는
 * {@link RefreshTokenStore}와 동일하게 이 클래스가 삼키지 않고 그대로 호출자에 전파한다 — 인증 필터마다
 * 실행되는 조회는 Fail-open으로 처리하기로 확정했으므로(Issue #186 Q5), 그 판단은 호출자가 맡는다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenBlacklistStore {

    private static final String KEY_PREFIX = "auth:access-token-blacklist:";

    private final StringRedisTemplate redisTemplate;

    /** ttl이 0 이하면(이미 만료됐거나 만료 직전) 등록을 건너뛴다 — 어차피 곧 스스로 무효가 된다. */
    public void blacklist(String jti, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(key(jti), "1", ttl);
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
    }

    private String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
