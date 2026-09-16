package com.bobfull.auth.infrastructure.redis;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Refresh Token의 발급·회전·삭제와 회원별 단일 세션을 Redis에서 관리한다.
// Redis 오류는 인증 흐름별 실패 정책을 결정하는 호출자에게 그대로 전파한다.
@Component
public class RefreshTokenStore {

    private static final String TOKEN_KEY_PREFIX = "auth:refresh-token:";
    private static final String MEMBER_KEY_PREFIX = "auth:refresh-token:member:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RefreshTokenStore(
            StringRedisTemplate redisTemplate,
            @Value("${auth.refresh-token.expiration-seconds}") long expirationSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(expirationSeconds);
    }

    public String issue(Long memberId) {
        deleteExistingTokenOf(memberId);
        return storeNewToken(memberId);
    }

    public Optional<RotatedToken> rotate(String refreshToken) {
        Long memberId = findMemberId(refreshToken).orElse(null);
        if (memberId == null) {
            return Optional.empty();
        }
        redisTemplate.delete(tokenKey(refreshToken));
        return Optional.of(new RotatedToken(memberId, storeNewToken(memberId)));
    }

    public void deleteByMember(Long memberId) {
        // 로그아웃 요청에는 Refresh Token이 없으므로 memberId 역방향 키로 기존 토큰을 찾는다.
        String existing = redisTemplate.opsForValue().get(memberKey(memberId));
        if (existing != null) {
            redisTemplate.delete(tokenKey(existing));
        }
        redisTemplate.delete(memberKey(memberId));
    }

    private Optional<Long> findMemberId(String refreshToken) {
        String value = redisTemplate.opsForValue().get(tokenKey(refreshToken));
        return Optional.ofNullable(value).map(Long::parseLong);
    }

    private void deleteExistingTokenOf(Long memberId) {
        String existing = redisTemplate.opsForValue().get(memberKey(memberId));
        if (existing != null) {
            redisTemplate.delete(tokenKey(existing));
        }
    }

    private String storeNewToken(Long memberId) {
        String refreshToken = generateToken();
        // 토큰 키와 회원 역방향 키의 TTL을 맞춰 단일 세션의 수명을 함께 관리한다.
        redisTemplate.opsForValue().set(tokenKey(refreshToken), memberId.toString(), ttl);
        redisTemplate.opsForValue().set(memberKey(memberId), refreshToken, ttl);
        return refreshToken;
    }

    private String tokenKey(String refreshToken) {
        return TOKEN_KEY_PREFIX + refreshToken;
    }

    private String memberKey(Long memberId) {
        return MEMBER_KEY_PREFIX + memberId;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RotatedToken(Long memberId, String refreshToken) {
    }
}
