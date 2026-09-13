package com.bobfull.auth.infrastructure.redis;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh Token을 Redis에만 저장·조회·삭제한다(Issue #125).
 * 회원당 Refresh Token은 한 번에 하나만 유효하며(단일 세션), 로그인·재발급은
 * 기존 토큰을 지우고 새 토큰을 발급한다. 로그아웃 요청 Body에는 토큰값이 없으므로
 * memberId → refreshToken 역방향 매핑도 함께 유지해 인증된 memberId만으로 삭제할 수 있게 한다.
 * Redis 조회 실패(연결 장애 등)는 이 클래스가 삼키지 않고 그대로 호출자에 전파한다.
 */
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
