package com.bobfull.restaurantinsight.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.common.config.ClockConfig;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.restaurantinsight.application.service.RestaurantFeedbackInsightService;
import com.bobfull.restaurantinsight.presentation.dto.RestaurantFeedbackInsightListResponse;
import com.bobfull.restaurantinsight.presentation.dto.RestaurantFeedbackInsightResponse;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 식당 피드백 인사이트 조회 API의 인증·인가와 익명 집계 응답을 검증한다.
 */
@WebMvcTest(controllers = RestaurantFeedbackInsightController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=restaurant-feedback-insight-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class RestaurantFeedbackInsightControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @MockitoBean
    private RestaurantFeedbackInsightService restaurantFeedbackInsightService;

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
    void 인증_없이_피드백_인사이트를_조회하면_401을_반환한다() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/owner/restaurants/10/feedback-insights"));

        result.andExpect(status().isUnauthorized());
    }

    @Test
    void OWNER_권한이_없으면_피드백_인사이트_조회는_403을_반환한다() throws Exception {
        ResultActions result = mockMvc.perform(
                get("/api/owner/restaurants/10/feedback-insights").with(authentication(memberAuthentication(1L))));

        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void OWNER가_피드백_인사이트를_조회하면_익명_집계_목록을_반환하고_식별값을_포함하지_않는다() throws Exception {
        // given
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        Instant from = now.minus(Duration.ofDays(7));
        RestaurantFeedbackInsightResponse item = new RestaurantFeedbackInsightResponse(
                "FOOD", "MENU", "탕수육", "TEXTURE", "POSITIVE", 8, "탕수육 식감에 대한 긍정 의견 8명");
        given(restaurantFeedbackInsightService.getOwnerInsights(1L, 10L))
                .willReturn(new RestaurantFeedbackInsightListResponse(10L, from, now, List.of(item)));

        // when
        ResultActions result = mockMvc.perform(
                get("/api/owner/restaurants/10/feedback-insights").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantId", is(10)))
                .andExpect(jsonPath("$.data.insights[0].category", is("FOOD")))
                .andExpect(jsonPath("$.data.insights[0].aspectType", is("MENU")))
                .andExpect(jsonPath("$.data.insights[0].normalizedAspect", is("탕수육")))
                .andExpect(jsonPath("$.data.insights[0].opinionType", is("TEXTURE")))
                .andExpect(jsonPath("$.data.insights[0].sentiment", is("POSITIVE")))
                .andExpect(jsonPath("$.data.insights[0].count", is(8)))
                .andExpect(jsonPath("$.data.insights[0].senderMemberId").doesNotExist())
                .andExpect(jsonPath("$.data.insights[0].messageId").doesNotExist())
                .andExpect(jsonPath("$.data.insights[0].nickname").doesNotExist());
    }
}
