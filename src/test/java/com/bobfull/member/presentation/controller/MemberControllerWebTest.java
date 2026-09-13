package com.bobfull.member.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.member.presentation.dto.MemberResponse;
import com.bobfull.member.presentation.dto.MemberUpdateRequest;
import com.bobfull.member.presentation.dto.MemberUpdateResponse;
import com.bobfull.member.application.service.MemberService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 내 정보 조회·수정 API의 인증 요구, 본인 정보 반환, 입력 검증을 검증한다.
 */
@WebMvcTest(controllers = MemberController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=member-controller-web-test-secret-key-please-keep-this-long-enough",
        "jwt.access-token-expiration-seconds=1800"
})
class MemberControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberService memberService;

    private Authentication authenticationOf(Long memberId, MemberRole role) {
        AuthMember authMember = new AuthMember(memberId, role);
        return new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    @Test
    void 인증_없이_내_정보를_조회하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/members/me"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void 인증된_회원은_본인_정보를_조회한다() throws Exception {
        // given
        given(memberService.getMe(1L))
                .willReturn(new MemberResponse(1L, "user@example.com", "홍길동", "01012345678", MemberRole.MEMBER, null));

        // when
        ResultActions result = mockMvc.perform(
                get("/api/members/me").with(authentication(authenticationOf(1L, MemberRole.MEMBER))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId", is(1)))
                .andExpect(jsonPath("$.data.businessNumber").doesNotExist());
    }

    @Test
    void OWNER가_본인_정보를_조회하면_사업자등록번호를_포함한다() throws Exception {
        // given
        given(memberService.getMe(2L)).willReturn(
                new MemberResponse(2L, "owner@example.com", "김사장", "01012345678", MemberRole.OWNER, "1234567890"));

        // when
        ResultActions result = mockMvc.perform(
                get("/api/members/me").with(authentication(authenticationOf(2L, MemberRole.OWNER))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessNumber", is("1234567890")));
    }

    @Test
    void 인증된_회원은_본인_정보를_수정한다() throws Exception {
        // given
        MemberUpdateRequest request = new MemberUpdateRequest("새이름", "01099998888");
        given(memberService.updateMe(1L, request)).willReturn(MemberUpdateResponse.success());

        // when
        ResultActions result = mockMvc.perform(patch("/api/members/me")
                .with(authentication(authenticationOf(1L, MemberRole.MEMBER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result", is(true)));
    }

    @Test
    void 이름이_비어있으면_내_정보_수정은_400을_반환한다() throws Exception {
        // given
        String invalidBody = """
                {"name":"","phoneNumber":"01099998888"}
                """;

        // when
        ResultActions result = mockMvc.perform(patch("/api/members/me")
                .with(authentication(authenticationOf(1L, MemberRole.MEMBER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }
}
