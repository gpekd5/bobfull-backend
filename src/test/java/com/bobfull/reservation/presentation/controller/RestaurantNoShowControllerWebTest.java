package com.bobfull.reservation.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.reservation.presentation.dto.NoShowCustomerResponse;
import com.bobfull.reservation.application.service.NoShowService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RestaurantNoShowController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=restaurant-no-show-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class RestaurantNoShowControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private NoShowService noShowService;

    @Test
    void OWNER가_식당_노쇼_고객을_조회한다() throws Exception {
        // given
        NoShowCustomerResponse customer = new NoShowCustomerResponse(
                20L, "홍○동", 2, OffsetDateTime.parse("2026-08-01T21:00:00+09:00"), 100L, 500L, 2);
        given(noShowService.getRestaurantNoShows(eq(1L), eq(10L), any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(customer), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/owner/restaurants/10/no-shows").with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].memberId", is(20)))
                .andExpect(jsonPath("$.data.content[0].noShowCount", is(2)))
                .andExpect(jsonPath("$.data.content[0].name", is("홍○동")));
    }

    @Test
    void OWNER가_아니면_식당_노쇼_고객_조회는_403을_반환한다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(member, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(get("/api/owner/restaurants/10/no-shows").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    private Authentication ownerAuthentication() {
        AuthMember owner = new AuthMember(1L, MemberRole.OWNER);
        return new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }
}
