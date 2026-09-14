package com.bobfull.admin.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.admin.presentation.dto.AdminMemberModerationDetailResponse;
import com.bobfull.admin.presentation.dto.AdminMemberModerationEvidenceResponse;
import com.bobfull.admin.presentation.dto.AdminMemberModerationListItemResponse;
import com.bobfull.admin.presentation.dto.MemberModerationReviewStatus;
import com.bobfull.admin.application.service.MemberModerationQueryService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

@WebMvcTest(controllers = AdminModerationController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=admin-moderation-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class AdminModerationControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private MemberModerationQueryService memberModerationQueryService;

    @Test
    void ADMIN은_목록에서_원문없이_검토대상_회원_집계를_조회한다() throws Exception {
        AdminMemberModerationListItemResponse item = new AdminMemberModerationListItemResponse(
                1L, 1L, 0L, 1L, 2L, 2L, MemberModerationReviewStatus.NORMAL, Instant.parse("2026-08-11T00:00:00Z"));
        given(memberModerationQueryService.getMemberModerations(isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/moderation/members").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].memberId", is(1)))
                .andExpect(jsonPath("$.data.content[0].content").doesNotExist());
    }

    @Test
    void ADMIN은_상세에서만_FLAGGED_원문을_조회한다() throws Exception {
        AdminMemberModerationEvidenceResponse evidence = new AdminMemberModerationEvidenceResponse(
                11L, "실제 근거 메시지", java.util.Set.of(), null, false, Instant.now(), Instant.now());
        given(memberModerationQueryService.getMemberModeration(1L)).willReturn(new AdminMemberModerationDetailResponse(
                1L, MemberModerationReviewStatus.NORMAL, 1L, 0L,
                Map.of("LOW", 1L, "MEDIUM", 0L, "HIGH", 0L), List.of(evidence)));

        mockMvc.perform(get("/api/admin/moderation/members/1").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidences[0].content", is("실제 근거 메시지")));
    }

    @Test
    void ADMIN이_아닌_회원은_moderation_관리자_API를_조회할수_없다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                member, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(get("/api/admin/moderation/members").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    private Authentication adminAuthentication() {
        AuthMember admin = new AuthMember(99L, MemberRole.ADMIN);
        return new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
