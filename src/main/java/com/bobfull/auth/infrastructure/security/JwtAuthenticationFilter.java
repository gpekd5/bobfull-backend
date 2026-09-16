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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Access Token을 검증해 인증 컨텍스트를 구성하고 로그아웃된 토큰을 차단한다.
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
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
                // jti가 없는 호환 토큰은 Blacklist 조회를 건너뛰고 인증을 유지한다.
                if (claims.jti() != null && isBlacklisted(claims.jti())) {
                    throw new InvalidJwtException("로그아웃된 Access Token입니다.");
                }
                SecurityContextHolder.getContext().setAuthentication(createAuthentication(claims.authMember()));
            } catch (InvalidJwtException e) {
                // 인증을 비운 채 체인을 계속해 보호 API를 AuthenticationEntryPoint의 401 경로로 보낸다.
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
            // 매 요청의 Redis 장애가 전체 API 중단으로 번지지 않도록 이 조회만 fail-open 처리한다.
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
