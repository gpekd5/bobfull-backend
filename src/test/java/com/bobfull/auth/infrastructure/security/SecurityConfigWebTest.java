package com.bobfull.auth.infrastructure.security;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.support.TestApiController;
import com.bobfull.member.domain.entity.MemberRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * SecurityConfig의 공개/인증 필요 API 구분, JWT 기반 인증, 역할 기반 접근 제어를 검증한다.
 */
@WebMvcTest(controllers = TestApiController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=security-config-web-test-secret-key-please-keep-this-long-enough",
        "jwt.access-token-expiration-seconds=1800",
        "cors.allowed-origins=http://localhost:5173"
})
@ActiveProfiles("test-api")
class SecurityConfigWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void 공개_API는_인증_없이_접근할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/restaurants"));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 경로_변수를_포함한_공개_API도_인증_없이_접근할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/restaurants/1/dining-sessions"));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 예약_검색_공개_API는_인증_없이_접근할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/reservations/search"));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 회원가입_로그인_등_인증_API는_인증_없이_접근할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(post("/api/auth/sample"));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 인증_필요_API에_인증_없이_접근하면_401_공통_실패_응답을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/protected/hello"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void 결제완료_preflight는_인증없이_통과하고_허용_origin을_반환한다() throws Exception {
        mockMvc.perform(options("/api/payments/test123/complete")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void 결제완료_POST는_인증없이_여전히_401이다() throws Exception {
        mockMvc.perform(post("/api/payments/test123/complete")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 인증된_회원이_접근하면_AuthMember가_컨트롤러에_전달된다() throws Exception {
        // given
        AuthMember authMember = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        // when
        ResultActions result = mockMvc.perform(get("/api/protected/hello").with(authentication(authentication)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(1)))
                .andExpect(jsonPath("$.data.role", is("MEMBER")));
    }

    @Test
    void 사장님_권한이_없는_회원이_사장님_전용_API에_접근하면_403_공통_실패_응답을_반환한다() throws Exception {
        // given
        AuthMember authMember = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        // when
        ResultActions result = mockMvc.perform(get("/api/owner/hello").with(authentication(authentication)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void 사장님_권한을_가진_회원은_사장님_전용_API에_접근할_수_있다() throws Exception {
        // given
        AuthMember authMember = new AuthMember(2L, MemberRole.OWNER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        // when
        ResultActions result = mockMvc.perform(get("/api/owner/hello").with(authentication(authentication)));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 관리자_권한이_없는_회원이_관리자_전용_API에_접근하면_403_공통_실패_응답을_반환한다() throws Exception {
        // given
        AuthMember authMember = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        // when
        ResultActions result = mockMvc.perform(get("/api/admin/hello").with(authentication(authentication)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void 관리자_권한을_가진_회원은_관리자_전용_API에_접근할_수_있다() throws Exception {
        // given
        AuthMember authMember = new AuthMember(3L, MemberRole.ADMIN);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        // when
        ResultActions result = mockMvc.perform(get("/api/admin/hello").with(authentication(authentication)));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 유효한_Access_Token이면_AuthMember가_등록되고_보호_API에_접근할_수_있다() throws Exception {
        // given
        String accessToken = jwtTokenProvider.createAccessToken(10L, MemberRole.OWNER);

        // when
        ResultActions result = mockMvc.perform(
                get("/api/protected/hello").header("Authorization", "Bearer " + accessToken));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(10)))
                .andExpect(jsonPath("$.data.role", is("OWNER")));
    }

    @Test
    void Authorization_헤더가_없으면_보호_API_접근시_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/protected/hello"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void 서명이_위조된_토큰이면_보호_API_접근시_401을_반환한다() throws Exception {
        // given
        String accessToken = jwtTokenProvider.createAccessToken(10L, MemberRole.MEMBER);
        String tamperedToken = tamperSignature(accessToken);

        // when
        ResultActions result = mockMvc.perform(
                get("/api/protected/hello").header("Authorization", "Bearer " + tamperedToken));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void 만료된_토큰이면_보호_API_접근시_401을_반환한다() throws Exception {
        // given
        Clock pastClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        JwtTokenProvider expiredTokenProvider = new JwtTokenProvider(
                pastClock, "security-config-web-test-secret-key-please-keep-this-long-enough", 1L);
        String expiredToken = expiredTokenProvider.createAccessToken(10L, MemberRole.MEMBER);

        // when
        ResultActions result = mockMvc.perform(
                get("/api/protected/hello").header("Authorization", "Bearer " + expiredToken));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void 형식이_올바르지_않은_토큰이면_보호_API_접근시_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(
                get("/api/protected/hello").header("Authorization", "Bearer not-a-jwt"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void Blacklist에_등록된_토큰이면_보호_API_접근시_401을_반환한다() throws Exception {
        // given: 서명·만료는 정상이지만 로그아웃돼 Blacklist에 등록된 것으로 가정(Issue #186)
        String accessToken = jwtTokenProvider.createAccessToken(10L, MemberRole.MEMBER);
        given(accessTokenBlacklistStore.isBlacklisted(anyString())).willReturn(true);

        // when
        ResultActions result = mockMvc.perform(
                get("/api/protected/hello").header("Authorization", "Bearer " + accessToken));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void Blacklist_조회가_Redis_장애로_실패해도_Fail_open으로_인증을_허용한다() throws Exception {
        // given: Issue #186 Q5 — Blacklist 조회는 인증되는 모든 요청에 실행되므로 Redis 장애 시
        // 보호 API 전체를 막지 않고 인증을 허용한다(재발급의 fail-closed와 의도적으로 다른 정책).
        String accessToken = jwtTokenProvider.createAccessToken(10L, MemberRole.MEMBER);
        given(accessTokenBlacklistStore.isBlacklisted(anyString()))
                .willThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        // when
        ResultActions result = mockMvc.perform(
                get("/api/protected/hello").header("Authorization", "Bearer " + accessToken));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(10)));
    }

    @Test
    void jti가_없는_배포_이전_토큰도_인증에_성공하고_Blacklist_조회는_건너뛴다() throws Exception {
        // given: Issue #186 배포 이전에 발급된 Access Token(PR #187 리뷰) — jti가 없어도 인증은
        // 그대로 허용하고, jti가 없으니 Blacklist 조회 자체를 시도하지 않아야 한다.
        String secret = "security-config-web-test-secret-key-please-keep-this-long-enough";
        SecretKey secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String tokenWithoutJti = Jwts.builder()
                .claim("memberId", 10L)
                .claim("role", MemberRole.MEMBER.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600L)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        // when
        ResultActions result = mockMvc.perform(
                get("/api/protected/hello").header("Authorization", "Bearer " + tokenWithoutJti));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(10)));
        Mockito.verifyNoInteractions(accessTokenBlacklistStore);
    }

    /**
     * 서명 부분의 첫 글자를 변조한다. 마지막 글자는 Base64url 인코딩의 padding 비트에 걸릴 수 있어
     * 디코딩 결과가 우연히 바뀌지 않을 수 있으므로(flaky), 항상 실제 바이트가 바뀌는 첫 글자를 사용한다.
     */
    private String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        char[] signatureChars = parts[2].toCharArray();
        signatureChars[0] = signatureChars[0] == 'a' ? 'b' : 'a';
        return parts[0] + "." + parts[1] + "." + new String(signatureChars);
    }
}
