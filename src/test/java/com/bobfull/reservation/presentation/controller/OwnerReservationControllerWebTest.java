package com.bobfull.reservation.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.reservation.presentation.response.OwnerReservationCancellationResponse;
import com.bobfull.reservation.presentation.response.OwnerReservationDetailResponse;
import com.bobfull.reservation.presentation.response.OwnerReservationParticipantResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.application.service.OwnerReservationCancellationService;
import com.bobfull.reservation.application.service.OwnerReservationQueryService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.time.OffsetDateTime;
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

@WebMvcTest(OwnerReservationController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=owner-reservation-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class OwnerReservationControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private OwnerReservationQueryService ownerReservationQueryService;
    @MockitoBean private OwnerReservationCancellationService ownerReservationCancellationService;

    @Test
    void OWNER가_본인_식당_예약_상세를_조회한다() throws Exception {
        // given
        OwnerReservationDetailResponse response = new OwnerReservationDetailResponse(
                1L, 10L, 100L, 1000L, 4,
                OffsetDateTime.parse("2026-08-01T18:00:00+09:00"), OffsetDateTime.parse("2026-08-01T20:00:00+09:00"),
                ReservationStatus.RECRUITING, RecruitmentStatus.OPEN, 2, 2, 3);
        given(ownerReservationQueryService.getReservationDetail(eq(1L), eq(1L))).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/owner/reservations/1").with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId", is(1)))
                .andExpect(jsonPath("$.data.restaurantId", is(10)))
                .andExpect(jsonPath("$.data.confirmationThreshold", is(3)));
    }

    @Test
    void OWNER가_아니면_예약_상세_조회는_403을_반환한다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(member, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(get("/api/owner/reservations/1").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void OWNER가_예약_참여자_목록을_조회한다() throws Exception {
        // given
        OwnerReservationParticipantResponse participant =
                new OwnerReservationParticipantResponse(10L, 20L, "홍길동", 2, ParticipationStatus.RESERVED);
        given(ownerReservationQueryService.getParticipants(eq(1L), eq(1L), org.mockito.ArgumentMatchers.any()))
                .willReturn(new PageResponse<>(List.of(participant), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/owner/reservations/1/participations").with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].memberId", is(20)))
                .andExpect(jsonPath("$.data.content[0].name", is("홍길동")));
    }

    @Test
    void OWNER가_예약_전체를_취소한다() throws Exception {
        // given
        given(ownerReservationCancellationService.cancel(eq(1L), eq(1L), any()))
                .willReturn(new OwnerReservationCancellationResponse(1L));

        // when & then
        mockMvc.perform(post("/api/owner/reservations/1/cancel")
                        .with(authentication(ownerAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"식당 내부 사정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId", is(1)));
    }

    @Test
    void OWNER가_아니면_전체_취소는_403을_반환한다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(member, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(post("/api/owner/reservations/1/cancel")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"식당 내부 사정\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void 본인_식당이_아니면_전체_취소는_403을_반환한다() throws Exception {
        given(ownerReservationCancellationService.cancel(eq(1L), eq(1L), any()))
                .willThrow(new CustomException(CommonErrorCode.ACCESS_DENIED));

        mockMvc.perform(post("/api/owner/reservations/1/cancel")
                        .with(authentication(ownerAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"식당 내부 사정\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void 존재하지_않는_예약이면_전체_취소는_404를_반환한다() throws Exception {
        given(ownerReservationCancellationService.cancel(eq(1L), eq(1L), any()))
                .willThrow(new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));

        mockMvc.perform(post("/api/owner/reservations/1/cancel")
                        .with(authentication(ownerAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"식당 내부 사정\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESERVATION_ID_NOT_FOUND")));
    }

    @Test
    void 이미_취소된_예약이면_전체_취소는_409를_반환한다() throws Exception {
        given(ownerReservationCancellationService.cancel(eq(1L), eq(1L), any()))
                .willThrow(new CustomException(ReservationErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/owner/reservations/1/cancel")
                        .with(authentication(ownerAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"식당 내부 사정\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE")));
    }

    @Test
    void 취소_사유가_없으면_전체_취소는_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/owner/reservations/1/cancel")
                        .with(authentication(ownerAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private Authentication ownerAuthentication() {
        AuthMember owner = new AuthMember(1L, MemberRole.OWNER);
        return new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }
}
