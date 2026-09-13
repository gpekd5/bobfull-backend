package com.bobfull.auth.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.auth.presentation.dto.LoginRequest;
import com.bobfull.auth.presentation.dto.LoginResponse;
import com.bobfull.auth.presentation.dto.LogoutResponse;
import com.bobfull.auth.presentation.dto.ReissueRequest;
import com.bobfull.auth.presentation.dto.ReissueResponse;
import com.bobfull.auth.presentation.dto.SignupOwnerRequest;
import com.bobfull.auth.presentation.dto.SignupResponse;
import com.bobfull.auth.presentation.dto.SignupUserRequest;
import com.bobfull.auth.application.service.AuthService;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 회원가입·로그인·재발급·로그아웃 API의 요청 검증, 응답 형식, 인증·인가 매핑을 검증한다.
 * /api/auth/**는 실제 운영에서 SecurityConfig의 permitAll(로그아웃 제외) 대상이라 같은 설정을 Import해 검증한다.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=auth-controller-web-test-secret-key-please-keep-this-long-enough",
        "jwt.access-token-expiration-seconds=1800"
})
class AuthControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AuthService authService;

    @Test
    void 일반_회원가입_성공시_201과_MEMBER_역할을_반환한다() throws Exception {
        // given
        SignupUserRequest request = new SignupUserRequest("user@example.com", "Password123!", "홍길동", "01012345678");
        given(authService.signupMember(request))
                .willReturn(new SignupResponse(1L, "user@example.com", "홍길동", MemberRole.MEMBER));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/signup/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role", is("MEMBER")));
    }

    @Test
    void 이메일_형식이_올바르지_않으면_400을_반환한다() throws Exception {
        // given
        String invalidBody = """
                {"email":"not-an-email","password":"Password123!","name":"홍길동","phoneNumber":"01012345678"}
                """;

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/signup/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 이메일이_중복되면_409와_DUPLICATE_EMAIL을_반환한다() throws Exception {
        // given
        SignupUserRequest request = new SignupUserRequest("dup@example.com", "Password123!", "홍길동", "01012345678");
        given(authService.signupMember(request)).willThrow(new CustomException(MemberErrorCode.DUPLICATE_EMAIL));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/signup/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_EMAIL")));
    }

    @Test
    void 사장님_회원가입_성공시_201과_OWNER_역할을_반환한다() throws Exception {
        // given
        SignupOwnerRequest request = new SignupOwnerRequest(
                "owner@example.com", "Password123!", "김사장", "01012345678", "1234567890");
        given(authService.signupOwner(request))
                .willReturn(new SignupResponse(2L, "owner@example.com", "김사장", MemberRole.OWNER));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/signup/owners")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role", is("OWNER")));
    }

    @Test
    void 로그인_성공시_AccessToken과_RefreshToken을_반환한다() throws Exception {
        // given
        LoginRequest request = new LoginRequest("user@example.com", "Password123!");
        given(authService.login(request)).willReturn(LoginResponse.of("access-token", "refresh-token"));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken", is("access-token")))
                .andExpect(jsonPath("$.data.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.data.refreshToken", is("refresh-token")));
    }

    @Test
    void 로그인_실패시_401과_INVALID_CREDENTIALS를_반환한다() throws Exception {
        // given
        LoginRequest request = new LoginRequest("user@example.com", "WrongPassword!");
        given(authService.login(request)).willThrow(new CustomException(MemberErrorCode.INVALID_CREDENTIALS));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
    }

    @Test
    void 유효한_RefreshToken으로_재발급하면_새_토큰_쌍을_반환한다() throws Exception {
        // given
        ReissueRequest request = new ReissueRequest("old-refresh-token");
        given(authService.reissue("old-refresh-token"))
                .willReturn(new ReissueResponse("new-access-token", "new-refresh-token"));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken", is("new-access-token")))
                .andExpect(jsonPath("$.data.refreshToken", is("new-refresh-token")));
    }

    @Test
    void 무효한_RefreshToken으로_재발급하면_401을_반환한다() throws Exception {
        // given
        ReissueRequest request = new ReissueRequest("invalid-refresh-token");
        given(authService.reissue("invalid-refresh-token"))
                .willThrow(new CustomException(CommonErrorCode.UNAUTHORIZED));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void 재발급요청에_RefreshToken이_없으면_400을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(post("/api/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 인증된_회원이_로그아웃하면_200을_반환한다() throws Exception {
        // given
        String accessToken = jwtTokenProvider.createAccessToken(1L, MemberRole.MEMBER);
        given(authService.logout(eq(1L), eq(accessToken))).willReturn(LogoutResponse.success());

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + accessToken));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result", is(true)));
    }

    @Test
    void 인증없이_로그아웃을_요청하면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

}
