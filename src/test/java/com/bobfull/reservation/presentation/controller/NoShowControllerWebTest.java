package com.bobfull.reservation.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.reservation.presentation.dto.NoShowCandidateResponse;
import com.bobfull.reservation.presentation.dto.NoShowHistoryResponse;
import com.bobfull.reservation.presentation.dto.NoShowProcessResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
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

@WebMvcTest(NoShowController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=no-show-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class NoShowControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private NoShowService noShowService;

    @Test
    void OWNER가_노쇼_후보_참여자를_조회한다() throws Exception {
        // given
        NoShowCandidateResponse candidate = new NoShowCandidateResponse(500L, 20L, "홍○동", 2, ParticipationStatus.RESERVED);
        given(noShowService.getCandidates(eq(1L), eq(10L), any()))
                .willReturn(new PageResponse<>(List.of(candidate), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/owner/reservations/10/participations/no-show-candidates")
                        .with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].participationId", is(500)))
                .andExpect(jsonPath("$.data.content[0].name", is("홍○동")));
    }

    @Test
    void OWNER가_참여자를_노쇼_처리한다() throws Exception {
        // given
        given(noShowService.markNoShow(1L, 10L, 500L)).willReturn(new NoShowProcessResponse(10L, 500L));

        // when & then
        mockMvc.perform(post("/api/owner/reservations/10/participations/500/no-show")
                        .with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId", is(10)))
                .andExpect(jsonPath("$.data.participationId", is(500)));
    }

    @Test
    void OWNER가_노쇼_처리를_해제한다() throws Exception {
        // given
        given(noShowService.unmarkNoShow(1L, 10L, 500L)).willReturn(new NoShowProcessResponse(10L, 500L));

        // when & then
        mockMvc.perform(delete("/api/owner/reservations/10/participations/500/no-show")
                        .with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participationId", is(500)));
    }

    @Test
    void OWNER가_예약별_노쇼_이력을_조회한다() throws Exception {
        // given
        NoShowHistoryResponse history = new NoShowHistoryResponse(
                1L, 500L, 20L, "홍○동", 2, true, 1L, OffsetDateTime.parse("2026-08-01T21:00:00+09:00"));
        given(noShowService.getHistories(eq(1L), eq(10L), any()))
                .willReturn(new PageResponse<>(List.of(history), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/owner/reservations/10/no-show-histories")
                        .with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].noShowHistoryId", is(1)))
                .andExpect(jsonPath("$.data.content[0].isMarked", is(true)));
    }

    @Test
    void OWNER가_아니면_노쇼_처리는_403을_반환한다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(member, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(post("/api/owner/reservations/10/participations/500/no-show")
                        .with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void 인증_없이_노쇼_후보_조회는_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/owner/reservations/10/participations/no-show-candidates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    private Authentication ownerAuthentication() {
        AuthMember owner = new AuthMember(1L, MemberRole.OWNER);
        return new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }
}
