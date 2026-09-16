package com.bobfull.auth.infrastructure.redis;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 로그아웃된 Access Token의 jti를 남은 유효시간 동안 Redis에서 관리한다.
@Component
@RequiredArgsConstructor
public class AccessTokenBlacklistStore {

    private static final String KEY_PREFIX = "auth:access-token-blacklist:";

    private final StringRedisTemplate redisTemplate;

    // 로그아웃 성공으로 오인되지 않도록 저장 실패를 호출자에게 전파한다.
    public void blacklist(String jti, Duration ttl) {
        // 남은 수명이 없으면 JWT 자체도 무효이므로 저장하지 않는다.
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(key(jti), "1", ttl);
    }

    // 인증 필터가 fail-open 여부를 결정하도록 조회 실패를 그대로 전파한다.
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
    }

    private String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
