package com.bobfull.auth.infrastructure.security;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.auth.infrastructure.jwt.InvalidJwtException;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청의 Authorization 헤더에서 Access Token을 추출·검증해 SecurityContext에 AuthMember를 등록한다.
 * 토큰이 없거나 유효하지 않으면 인증을 설정하지 않고 다음 필터로 넘겨,
 * 보호 API는 AuthenticationEntryPoint가 401로 응답하도록 한다.
 * 로그아웃된 Access Token은 Blacklist 조회로 추가 차단한다(Issue #186). 이 조회는 인증되는 모든
 * 요청마다 실행되므로 Redis 장애 시 요청을 막지 않는 Fail-open으로 처리한다(Issue #186 Q5) — 장애가
 * 전체 API 중단으로 번지지 않게 하는 것이 우선이며, 노출되는 위험은 직전 로그아웃한 토큰이 만료
 * 시각까지 잠시 재사용되는 좁은 범위뿐이다. jti가 없는 토큰(이 기능 배포 이전에 발급된 토큰)은
 * Blacklist 조회 자체를 건너뛰고 인증만 정상 처리한다 — 배포 순간 활성 세션 전원이 강제 로그아웃되는
 * 것을 막기 위함이며(PR #187 리뷰), 어차피 그런 토큰은 Blacklist에 등록될 수도 없다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklistStore accessTokenBlacklistStore;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, AccessTokenBlacklistStore accessTokenBlacklistStore) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.accessTokenBlacklistStore = accessTokenBlacklistStore;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null) {
            try {
                JwtTokenProvider.AccessTokenClaims claims = jwtTokenProvider.parseAccessTokenClaims(token);
                if (claims.jti() != null && isBlacklisted(claims.jti())) {
                    throw new InvalidJwtException("로그아웃된 Access Token입니다.");
                }
                SecurityContextHolder.getContext().setAuthentication(createAuthentication(claims.authMember()));
            } catch (InvalidJwtException e) {
                SecurityContextHolder.clearContext();
                if (!isTokenExpired(e)) {
                    log.warn("event=JWT_INVALID reason=INVALID_JWT path={}", request.getRequestURI());
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isBlacklisted(String jti) {
        try {
            return accessTokenBlacklistStore.isBlacklisted(jti);
        } catch (DataAccessException e) {
            log.warn("event=ACCESS_TOKEN_BLACKLIST_CHECK_FAILED jti={} reason={}", jti, e.toString());
            return false;
        }
    }

    private boolean isTokenExpired(InvalidJwtException exception) {
        return exception.getCause() instanceof ExpiredJwtException;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/webhooks/portone".equals(request.getRequestURI());
    }

    private Authentication createAuthentication(AuthMember authMember) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + authMember.role().name());
        return new UsernamePasswordAuthenticationToken(authMember, null, List.of(authority));
    }
}
