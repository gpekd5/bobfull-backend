package com.bobfull.reservation.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.reservation.presentation.dto.MyReservationDetailResponse;
import com.bobfull.reservation.presentation.dto.MyReservationListItemResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.application.service.MyReservationQueryService;
import com.bobfull.common.response.PageResponse;
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

@WebMvcTest(controllers = MyReservationController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=my-reservation-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class MyReservationControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private MyReservationQueryService myReservationQueryService;

    @Test
    void 인증된_회원이_내_예약_목록을_페이징_형식으로_조회한다() throws Exception {
        // given
        MyReservationListItemResponse item = new MyReservationListItemResponse(
                1L, 10L, "밥풀식당", 100L,
                OffsetDateTime.parse("2026-08-01T18:00:00+09:00"), OffsetDateTime.parse("2026-08-01T20:00:00+09:00"),
                ReservationStatus.RECRUITING, RecruitmentStatus.OPEN,
                20L, 2, ParticipationStatus.RESERVED, PaymentStatus.PAID);
        given(myReservationQueryService.getMyReservations(eq(1L), isNull(), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/members/me/reservations").with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reservationId", is(1)))
                .andExpect(jsonPath("$.data.content[0].restaurantName", is("밥풀식당")))
                .andExpect(jsonPath("$.data.totalElements", is(1)));
    }

    @Test
    void 인증없이_내_예약_목록을_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/members/me/reservations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 인증된_회원이_내_예약_상세를_조회한다() throws Exception {
        // given
        MyReservationDetailResponse response = new MyReservationDetailResponse(
                1L, 10L, "밥풀식당", 100L,
                OffsetDateTime.parse("2026-08-01T18:00:00+09:00"), OffsetDateTime.parse("2026-08-01T20:00:00+09:00"),
                ReservationStatus.RECRUITING, RecruitmentStatus.OPEN,
                20L, 2, ParticipationStatus.RESERVED, "payment-id-1", PaymentStatus.PAID);
        given(myReservationQueryService.getMyReservationDetail(1L, 1L)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/members/me/reservations/1").with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId", is("payment-id-1")));
    }

    @Test
    void 인증없이_내_예약_상세를_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/members/me/reservations/1"))
                .andExpect(status().isUnauthorized());
    }

    private Authentication memberAuthentication(Long memberId) {
        AuthMember authMember = new AuthMember(memberId, MemberRole.MEMBER);
        return new UsernamePasswordAuthenticationToken(authMember, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
}
