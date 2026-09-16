package com.bobfull.payment.presentation.controller;

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
import com.bobfull.payment.presentation.response.ExpectedSettlementResponse;
import com.bobfull.payment.presentation.response.SettlementReservationResponse;
import com.bobfull.payment.application.service.SettlementQueryService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.math.BigDecimal;
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

@WebMvcTest(SettlementController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=settlement-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class SettlementControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private SettlementQueryService settlementQueryService;

    @Test
    void OWNER가_지급예정금액을_조회한다() throws Exception {
        // given
        given(settlementQueryService.getExpectedSettlement(1L, 10L, null, null))
                .willReturn(new ExpectedSettlementResponse(BigDecimal.valueOf(30000), BigDecimal.TEN, BigDecimal.valueOf(29990)));

        // when & then
        mockMvc.perform(get("/api/owner/restaurants/10/settlements/expected").with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expectedSettlementAmount", is(29990)));
    }

    @Test
    void OWNER가_예약별지급예정내역을_조회한다() throws Exception {
        // given
        SettlementReservationResponse item = new SettlementReservationResponse(3L,
                OffsetDateTime.parse("2026-08-01T18:00:00+09:00"), BigDecimal.valueOf(30000), BigDecimal.ZERO, BigDecimal.valueOf(30000));
        given(settlementQueryService.getReservationSettlements(eq(1L), eq(10L), any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/owner/restaurants/10/settlements/reservations?startDate=2026-08-01")
                .with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reservationId", is(3)))
                .andExpect(jsonPath("$.data.page", is(0)))
                .andExpect(jsonPath("$.data.size", is(20)))
                .andExpect(jsonPath("$.data.totalElements", is(1)))
                .andExpect(jsonPath("$.data.totalPages", is(1)))
                .andExpect(jsonPath("$.data.restaurantId").doesNotExist())
                .andExpect(jsonPath("$.data.startDate").doesNotExist())
                .andExpect(jsonPath("$.data.endDate").doesNotExist());
    }

    @Test
    void OWNER가_아니면_정산조회는_403을_반환한다() throws Exception {
        AuthMember member = new AuthMember(1L, MemberRole.MEMBER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(member, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        mockMvc.perform(get("/api/owner/restaurants/10/settlements/expected").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    private Authentication ownerAuthentication() {
        AuthMember owner = new AuthMember(1L, MemberRole.OWNER);
        return new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }
}
