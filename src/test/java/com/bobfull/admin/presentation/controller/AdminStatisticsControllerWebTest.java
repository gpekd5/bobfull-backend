package com.bobfull.admin.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.admin.presentation.response.AdminMemberNoShowRateResponse;
import com.bobfull.admin.presentation.response.AdminOverviewStatisticsResponse;
import com.bobfull.admin.presentation.response.AdminRestaurantStatisticsResponse;
import com.bobfull.admin.application.service.AdminStatisticsQueryService;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
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

@WebMvcTest(controllers = AdminStatisticsController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=admin-statistics-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class AdminStatisticsControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private AdminStatisticsQueryService adminStatisticsQueryService;

    @Test
    void ADMIN이_전체_운영_지표를_조회한다() throws Exception {
        given(adminStatisticsQueryService.getOverview())
                .willReturn(new AdminOverviewStatisticsResponse(1000L, 78.0, 3.5));

        mockMvc.perform(get("/api/admin/statistics/overview").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noShowRate", is(3.5)));
    }

    @Test
    void ADMIN이_식당별_성사율을_조회한다() throws Exception {
        AdminRestaurantStatisticsResponse item = new AdminRestaurantStatisticsResponse(1L, "밥풀식당", 120L, 90L, 75.0);
        given(adminStatisticsQueryService.getRestaurantStatistics(isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/statistics/restaurants").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].confirmationRate", is(75.0)));
    }

    @Test
    void ADMIN이_사용자별_노쇼율을_조회한다() throws Exception {
        AdminMemberNoShowRateResponse item = new AdminMemberNoShowRateResponse(1L, "홍○동", 10L, 2L, 20.0);
        given(adminStatisticsQueryService.getMemberNoShowRates(any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/statistics/members/no-show-rates").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name", is("홍○동")));
    }

    @Test
    void 인증없이_운영_지표를_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/statistics/overview")).andExpect(status().isUnauthorized());
    }

    private Authentication adminAuthentication() {
        AuthMember admin = new AuthMember(99L, MemberRole.ADMIN);
        return new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
