package com.bobfull.admin.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.admin.presentation.dto.AdminNoShowListItemResponse;
import com.bobfull.admin.application.service.AdminNoShowQueryService;
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

@WebMvcTest(controllers = AdminNoShowController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=admin-no-show-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class AdminNoShowControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private AdminNoShowQueryService adminNoShowQueryService;

    @Test
    void ADMIN이_전체_노쇼_현황을_조회한다() throws Exception {
        AdminNoShowListItemResponse item = new AdminNoShowListItemResponse(
                1L, 15L, "김○○", 1L, "밥풀식당", 101L, 501L, 2,
                OffsetDateTime.parse("2026-07-25T21:00:00+09:00"));
        given(adminNoShowQueryService.getNoShows(isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/no-shows").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].noShowHistoryId", is(1)))
                .andExpect(jsonPath("$.data.content[0].memberName", is("김○○")))
                .andExpect(jsonPath("$.data.content[0].restaurantName", is("밥풀식당")));
    }

    @Test
    void 인증없이_전체_노쇼_현황을_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/no-shows")).andExpect(status().isUnauthorized());
    }

    @Test
    void ADMIN이_아니면_전체_노쇼_현황_조회는_403을_반환한다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(member, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(get("/api/admin/no-shows").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    private Authentication adminAuthentication() {
        AuthMember admin = new AuthMember(99L, MemberRole.ADMIN);
        return new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
