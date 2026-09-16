package com.bobfull.admin.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.admin.presentation.response.AdminRestaurantListItemResponse;
import com.bobfull.admin.application.service.AdminRestaurantQueryService;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
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

@WebMvcTest(controllers = AdminRestaurantController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=admin-restaurant-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class AdminRestaurantControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private AdminRestaurantQueryService adminRestaurantQueryService;

    @Test
    void ADMIN이_식당_목록을_조회한다() throws Exception {
        AdminRestaurantListItemResponse item = new AdminRestaurantListItemResponse(
                1L, 3L, "김사장", "밥풀식당", "한식", RestaurantStatus.ACTIVE,
                OffsetDateTime.parse("2026-08-01T00:00:00+09:00"));
        given(adminRestaurantQueryService.getRestaurants(isNull(), isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/restaurants").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name", is("밥풀식당")));
    }

    @Test
    void 인증없이_식당_목록을_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/restaurants")).andExpect(status().isUnauthorized());
    }

    private Authentication adminAuthentication() {
        AuthMember admin = new AuthMember(99L, MemberRole.ADMIN);
        return new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
