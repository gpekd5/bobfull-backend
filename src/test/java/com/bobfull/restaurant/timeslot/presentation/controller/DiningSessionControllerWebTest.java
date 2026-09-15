package com.bobfull.restaurant.timeslot.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.timeslot.domain.exception.TimeSlotErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.restaurant.timeslot.presentation.response.AvailableDiningSessionListResponse;
import com.bobfull.restaurant.timeslot.presentation.response.AvailableDiningSessionResponse;
import com.bobfull.restaurant.timeslot.presentation.request.DiningSessionBulkRequest;
import com.bobfull.restaurant.timeslot.presentation.response.DiningSessionBulkResponse;
import com.bobfull.restaurant.timeslot.presentation.response.DiningSessionIdResponse;
import com.bobfull.restaurant.timeslot.presentation.request.DiningSessionRequest;
import com.bobfull.restaurant.timeslot.presentation.response.DiningSessionResponse;
import com.bobfull.restaurant.timeslot.application.service.TimeSlotService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

@WebMvcTest(controllers = DiningSessionController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=dining-session-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class DiningSessionControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TimeSlotService timeSlotService;

    private Authentication ownerAuthentication(Long memberId) {
        AuthMember authMember = new AuthMember(memberId, MemberRole.OWNER);
        return new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private Authentication memberAuthentication(Long memberId) {
        AuthMember authMember = new AuthMember(memberId, MemberRole.MEMBER);
        return new UsernamePasswordAuthenticationToken(
                authMember, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }

    @Test
    void 인증_없이_회차를_등록하면_401을_반환한다() throws Exception {
        // given
        DiningSessionRequest request = request();

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/tables/100/dining-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void OWNER_권한이_없는_회원이_회차를_등록하면_403을_반환한다() throws Exception {
        // given
        DiningSessionRequest request = request();

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/tables/100/dining-sessions")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void OWNER가_회차를_등록하면_201과_sessionId를_반환한다() throws Exception {
        // given
        DiningSessionRequest request = request();
        given(timeSlotService.register(eq(1L), eq(100L), any(DiningSessionRequest.class)))
                .willReturn(new DiningSessionIdResponse(200L));

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/tables/100/dining-sessions")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId", is(200)));
    }

    @Test
    void endAt이_없으면_회차_등록은_400을_반환한다() throws Exception {
        // given
        String invalidBody = "{\"startAt\":\"2026-08-01T11:00:00\"}";

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/tables/100/dining-sessions")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 중복_회차를_등록하면_409를_반환한다() throws Exception {
        // given
        DiningSessionRequest request = request();
        given(timeSlotService.register(eq(1L), eq(100L), any(DiningSessionRequest.class)))
                .willThrow(new CustomException(TimeSlotErrorCode.DUPLICATE_DINING_SESSION));

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/tables/100/dining-sessions")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_DINING_SESSION")));
    }

    @Test
    void 기존_테이블에_회차를_일괄_등록한다() throws Exception {
        // given
        DiningSessionBulkRequest request = new DiningSessionBulkRequest(
                List.of(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2)),
                LocalTime.of(11, 0),
                LocalTime.of(13, 0),
                60
        );
        given(timeSlotService.registerBulk(eq(1L), eq(100L), any(DiningSessionBulkRequest.class)))
                .willReturn(new DiningSessionBulkResponse(100L, 4));

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/tables/100/dining-sessions/bulk")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tableId", is(100)))
                .andExpect(jsonPath("$.data.createdSessionCount", is(4)));
    }

    @Test
    void 본인_식당의_회차_목록을_페이징으로_조회한다() throws Exception {
        // given
        DiningSessionResponse item = new DiningSessionResponse(
                200L,
                100L,
                4,
                OffsetDateTime.parse("2026-08-01T11:00:00+09:00"),
                OffsetDateTime.parse("2026-08-01T13:00:00+09:00")
        );
        PageResponse<DiningSessionResponse> page = new PageResponse<>(List.of(item), 0, 20, 1, 1);
        given(timeSlotService.getOwnerDiningSessions(eq(1L), eq(10L), eq(LocalDate.of(2026, 8, 1)), any(Pageable.class)))
                .willReturn(page);

        // when
        ResultActions result = mockMvc.perform(get("/api/owner/restaurants/10/dining-sessions")
                .with(authentication(ownerAuthentication(1L)))
                .param("date", "2026-08-01"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].sessionId", is(200)))
                .andExpect(jsonPath("$.data.content[0].startAt", is("2026-08-01T11:00:00+09:00")))
                .andExpect(jsonPath("$.data.totalElements", is(1)));
    }

    @Test
    void 사용자용_예약_가능_회차는_인증_없이_조회한다() throws Exception {
        // given
        AvailableDiningSessionResponse item = new AvailableDiningSessionResponse(
                200L,
                100L,
                4,
                OffsetDateTime.parse("2026-08-01T11:00:00+09:00"),
                OffsetDateTime.parse("2026-08-01T13:00:00+09:00"),
                4,
                null,
                0
        );
        given(timeSlotService.getAvailableDiningSessions(10L, LocalDate.of(2026, 8, 1), 2))
                .willReturn(new AvailableDiningSessionListResponse(10L, List.of(item)));

        // when
        ResultActions result = mockMvc.perform(get("/api/restaurants/10/dining-sessions")
                .param("date", "2026-08-01")
                .param("partySize", "2"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantId", is(10)))
                .andExpect(jsonPath("$.data.content[0].availableCapacity", is(4)));
    }

    @Test
    void 사용자용_예약_가능_회차_조회에서_date가_없으면_400을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/restaurants/10/dining-sessions"));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 회차를_수정한다() throws Exception {
        // given
        DiningSessionRequest request = request();
        given(timeSlotService.update(eq(1L), eq(200L), any(DiningSessionRequest.class)))
                .willReturn(new DiningSessionIdResponse(200L));

        // when
        ResultActions result = mockMvc.perform(patch("/api/owner/dining-sessions/200")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId", is(200)));
    }

    @Test
    void 연결된_예약이_있으면_회차_수정은_409를_반환한다() throws Exception {
        // given
        DiningSessionRequest request = request();
        given(timeSlotService.update(eq(1L), eq(200L), any(DiningSessionRequest.class)))
                .willThrow(new CustomException(TimeSlotErrorCode.SESSION_HAS_RESERVATION));

        // when
        ResultActions result = mockMvc.perform(patch("/api/owner/dining-sessions/200")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SESSION_HAS_RESERVATION")));
    }

    @Test
    void 회차를_삭제한다() throws Exception {
        // given
        given(timeSlotService.delete(1L, 200L)).willReturn(new DiningSessionIdResponse(200L));

        // when
        ResultActions result = mockMvc.perform(delete("/api/owner/dining-sessions/200")
                .with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId", is(200)));
    }

    private DiningSessionRequest request() {
        return new DiningSessionRequest(
                LocalDateTime.of(2026, 8, 1, 11, 0),
                LocalDateTime.of(2026, 8, 1, 13, 0)
        );
    }
}
