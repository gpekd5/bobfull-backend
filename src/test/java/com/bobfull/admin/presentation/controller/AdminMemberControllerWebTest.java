package com.bobfull.admin.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.admin.presentation.response.AdminMemberDetailResponse;
import com.bobfull.admin.presentation.response.AdminMemberListItemResponse;
import com.bobfull.admin.application.service.AdminMemberQueryService;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminMemberController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=admin-member-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class AdminMemberControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private AdminMemberQueryService adminMemberQueryService;

    @Test
    void ADMIN이_회원_목록을_조회한다() throws Exception {
        AdminMemberListItemResponse item = new AdminMemberListItemResponse(
                1L, "user@example.com", "홍길동", MemberRole.MEMBER, 0L,
                OffsetDateTime.parse("2026-08-01T00:00:00+09:00"), null);
        given(adminMemberQueryService.getMembers(isNull(), isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/members").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].email", is("user@example.com")));
    }

    @Test
    void ADMIN이_회원_상세를_조회한다() throws Exception {
        AdminMemberDetailResponse response = new AdminMemberDetailResponse(
                1L, "user@example.com", "홍길동", "01011112222", MemberRole.MEMBER, 0L,
                OffsetDateTime.parse("2026-08-01T00:00:00+09:00"), null);
        given(adminMemberQueryService.getMember(1L)).willReturn(response);

        mockMvc.perform(get("/api/admin/members/1").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneNumber", is("01011112222")));
    }

    @Test
    void 인증없이_회원_목록을_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void ADMIN이_아닌_회원이_조회하면_403을_반환한다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                member, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(get("/api/admin/members").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    private Authentication adminAuthentication() {
        AuthMember admin = new AuthMember(99L, MemberRole.ADMIN);
        return new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
