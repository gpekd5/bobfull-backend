package com.bobfull.admin.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.admin.dto.AdminReservationListItemResponse;
import com.bobfull.admin.service.AdminReservationQueryService;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
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

@WebMvcTest(controllers = AdminReservationController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=admin-reservation-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class AdminReservationControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private AdminReservationQueryService adminReservationQueryService;

    @Test
    void ADMIN이_전체_예약_현황을_조회한다() throws Exception {
        AdminReservationListItemResponse item = new AdminReservationListItemResponse(
                1L, 2L, "밥풀식당", 15L, OffsetDateTime.parse("2026-08-01T18:00:00+09:00"),
                ReservationStatus.CONFIRMED, RecruitmentStatus.OPEN, 3, 4);
        given(adminReservationQueryService.getReservations(isNull(), isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(get("/api/admin/reservations").with(authentication(adminAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].restaurantName", is("밥풀식당")));
    }

    @Test
    void 인증없이_예약_현황을_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/reservations")).andExpect(status().isUnauthorized());
    }

    private Authentication adminAuthentication() {
        AuthMember admin = new AuthMember(99L, MemberRole.ADMIN);
        return new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
