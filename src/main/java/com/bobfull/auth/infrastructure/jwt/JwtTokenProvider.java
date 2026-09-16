package com.bobfull.auth.infrastructure.jwt;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

// Access Token을 발급하고 서명·Claim·만료를 검증한다.
// Jackson 3과의 버전 충돌을 피하기 위해 JJWT는 Gson 구현을 사용한다.
public class JwtTokenProvider {

    private static final String CLAIM_MEMBER_ID = "memberId";
    private static final String CLAIM_ROLE = "role";

    private final Clock clock;
    private final SecretKey secretKey;
    private final long accessTokenExpirationSeconds;

    public JwtTokenProvider(Clock clock, String secret, long accessTokenExpirationSeconds) {
        this.clock = clock;
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }

    public String createAccessToken(Long memberId, MemberRole role) {
        Instant now = clock.instant();
        Instant expiration = now.plusSeconds(accessTokenExpirationSeconds);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_MEMBER_ID, memberId)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    // 검증된 Access Token에서 인증 컨텍스트에 사용할 회원 정보를 만든다.
    public AuthMember parseAccessToken(String token) {
        return parseAccessTokenClaims(token).authMember();
    }

    // Blacklist 처리에 필요한 jti와 만료 시각을 포함해 Access Token Claim을 검증한다.
    public AccessTokenClaims parseAccessTokenClaims(String token) {
        try {
            var claims = Jwts.parser()
                    .clock(() -> Date.from(clock.instant()))
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Number memberId = claims.get(CLAIM_MEMBER_ID, Number.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            Date expiration = claims.getExpiration();
            if (memberId == null || role == null || expiration == null) {
                throw new InvalidJwtException("토큰에 필수 Claim이 없습니다.");
            }

            AuthMember authMember = new AuthMember(memberId.longValue(), MemberRole.valueOf(role));
            // jti가 없는 호환 토큰도 인증하며, 호출자가 Blacklist 처리만 건너뛴다.
            return new AccessTokenClaims(authMember, claims.getId(), expiration.toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            // 검증 실패 원인을 통일해 인증 필터가 하나의 401 경로로 처리하도록 한다.
            throw new InvalidJwtException("토큰을 검증할 수 없습니다.", e);
        }
    }

    // 호환 토큰은 jti가 없을 수 있다.
    public record AccessTokenClaims(AuthMember authMember, String jti, Instant expiresAt) {
    }
}
