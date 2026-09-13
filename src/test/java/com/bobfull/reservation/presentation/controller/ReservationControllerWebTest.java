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
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.reservation.domain.CancellationScope;
import com.bobfull.reservation.presentation.dto.ReservationCancellationRequest;
import com.bobfull.reservation.presentation.dto.ReservationCancellationResponse;
import com.bobfull.reservation.presentation.dto.ReservationSearchRequest;
import com.bobfull.reservation.presentation.dto.ReservationSearchResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.presentation.dto.ReservationAvailabilityResponse;
import com.bobfull.reservation.presentation.dto.ReservationPrepareRequest;
import com.bobfull.reservation.presentation.dto.ReservationPrepareResponse;
import com.bobfull.reservation.application.service.ReservationCancellationService;
import com.bobfull.reservation.application.service.ReservationPreparationService;
import com.bobfull.reservation.application.service.ReservationSearchService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = ReservationController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=reservation-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class ReservationControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationPreparationService reservationPreparationService;

    @MockitoBean
    private ReservationSearchService reservationSearchService;

    @MockitoBean
    private ReservationCancellationService reservationCancellationService;

    private Authentication memberAuthentication(Long memberId) {
        AuthMember authMember = new AuthMember(memberId, MemberRole.MEMBER);
        return new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }

    @Test
    void 인증_없이_모집중_예약을_검색할_수_있다() throws Exception {
        // given
        PageResponse<ReservationSearchResponse> page = new PageResponse<>(
                List.of(new ReservationSearchResponse(
                        1L,
                        10L,
                        "밥풀식당",
                        100L,
                        200L,
                        4,
                        OffsetDateTime.parse("2026-08-01T18:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-01T20:00:00+09:00"),
                        ReservationStatus.RECRUITING,
                        RecruitmentStatus.OPEN,
                        2,
                        2,
                        3
                )),
                0, 20, 1, 1);
        given(reservationSearchService.searchReservations(
                any(ReservationSearchRequest.class), any(Pageable.class))).willReturn(page);

        // when
        ResultActions result = mockMvc.perform(get("/api/reservations/search")
                .param("keyword", "밥풀")
                .param("date", "2026-08-01")
                .param("time", "18:00")
                .param("capacity", "4")
                .param("minimumRemainingSeats", "2"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reservationId", is(1)))
                .andExpect(jsonPath("$.data.content[0].restaurantName", is("밥풀식당")))
                .andExpect(jsonPath("$.data.content[0].recruitmentStatus", is("OPEN")))
                .andExpect(jsonPath("$.data.content[0].availableCapacity", is(2)));
    }

    @Test
    void 인증_없이_예약_가능_여부를_조회하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/reservations/availability")
                .param("type", "CREATE").param("targetId", "200").param("partySize", "2"));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void 예약_가능_여부를_조회한다() throws Exception {
        // given
        given(reservationPreparationService.checkAvailability(1L, PaymentPurpose.CREATE, 200L, 2))
                .willReturn(ReservationAvailabilityResponse.available(4));

        // when
        ResultActions result = mockMvc.perform(get("/api/reservations/availability")
                .with(authentication(memberAuthentication(1L)))
                .param("type", "CREATE").param("targetId", "200").param("partySize", "2"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available", is(true)))
                .andExpect(jsonPath("$.data.availableCapacity", is(4)));
    }

    @Test
    void 활성_예약이_이미_있으면_409를_반환한다() throws Exception {
        // given
        given(reservationPreparationService.checkAvailability(eq(1L), eq(PaymentPurpose.CREATE), eq(200L), any()))
                .willThrow(new CustomException(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS));

        // when
        ResultActions result = mockMvc.perform(get("/api/reservations/availability")
                .with(authentication(memberAuthentication(1L)))
                .param("type", "CREATE").param("targetId", "200").param("partySize", "2"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ACTIVE_RESERVATION_ALREADY_EXISTS")));
    }

    @Test
    void 결제를_준비하면_paymentId와_만료시각을_반환한다() throws Exception {
        // given
        ReservationPrepareRequest request = new ReservationPrepareRequest(PaymentPurpose.CREATE, 200L, 2);
        given(reservationPreparationService.prepare(eq(1L), any(ReservationPrepareRequest.class)))
                .willReturn(new ReservationPrepareResponse("payment-id", PaymentStatus.READY, BigDecimal.valueOf(20000),
                        OffsetDateTime.parse("2026-07-25T17:10:00+09:00")));

        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/prepare")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId", is("payment-id")))
                .andExpect(jsonPath("$.data.paymentStatus", is("READY")));
    }

    @Test
    void partySize가_없으면_결제_준비는_400을_반환한다() throws Exception {
        // given
        String invalidBody = "{\"type\":\"CREATE\",\"targetId\":200}";

        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/prepare")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 내_참여를_취소한다() throws Exception {
        // given: 취소는 접수만 되고(CANCEL_REQUESTED), 실제 CANCELLED 확정은 환불 완료 후 이뤄진다(Issue #44)
        ReservationCancellationRequest request = new ReservationCancellationRequest("개인 일정 변경");
        given(reservationCancellationService.cancel(eq(1L), eq(10L), any(ReservationCancellationRequest.class)))
                .willReturn(new ReservationCancellationResponse(
                        10L, 20L, ParticipationStatus.CANCEL_REQUESTED, CancellationScope.PARTICIPATION, "REQUESTED"));

        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/10/participations/me/cancel")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId", is(10)))
                .andExpect(jsonPath("$.data.participationId", is(20)))
                .andExpect(jsonPath("$.data.participationStatus", is("CANCEL_REQUESTED")))
                .andExpect(jsonPath("$.data.cancellationScope", is("PARTICIPATION")))
                .andExpect(jsonPath("$.data.refundStatus", is("REQUESTED")));
    }

    @Test
    void 인증_없이_취소를_요청하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/10/participations/me/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReservationCancellationRequest("사유"))));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void 취소_사유가_비어있으면_400을_반환한다() throws Exception {
        // given
        String invalidBody = "{\"reason\":\"\"}";

        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/10/participations/me/cancel")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 취소_사유가_255자를_초과하면_400을_반환한다() throws Exception {
        // given
        String invalidBody = objectMapper.writeValueAsString(
                new ReservationCancellationRequest("가".repeat(256)));

        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/10/participations/me/cancel")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 본인_참여가_아니면_403을_반환한다() throws Exception {
        // given
        given(reservationCancellationService.cancel(eq(1L), eq(10L), any(ReservationCancellationRequest.class)))
                .willThrow(new CustomException(CommonErrorCode.ACCESS_DENIED));

        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/10/participations/me/cancel")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReservationCancellationRequest("사유"))));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void 취소_기한이_지나면_409를_반환한다() throws Exception {
        // given
        given(reservationCancellationService.cancel(eq(1L), eq(10L), any(ReservationCancellationRequest.class)))
                .willThrow(new CustomException(ReservationErrorCode.CANCELLATION_DEADLINE_PASSED));

        // when
        ResultActions result = mockMvc.perform(post("/api/reservations/10/participations/me/cancel")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReservationCancellationRequest("사유"))));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CANCELLATION_DEADLINE_PASSED")));
    }
}
