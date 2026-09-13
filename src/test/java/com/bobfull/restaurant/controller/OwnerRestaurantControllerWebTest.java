package com.bobfull.restaurant.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.restaurant.dto.OwnerRestaurantDetailResponse;
import com.bobfull.restaurant.dto.OwnerRestaurantListResponse;
import com.bobfull.restaurant.dto.RestaurantCreateRequest;
import com.bobfull.restaurant.dto.RestaurantIdResponse;
import com.bobfull.restaurant.dto.RestaurantUpdateRequest;
import com.bobfull.restaurant.entity.RestaurantStatus;
import com.bobfull.restaurant.service.RestaurantService;
import com.bobfull.restaurantinsight.dto.RestaurantFeedbackInsightListResponse;
import com.bobfull.restaurantinsight.dto.RestaurantFeedbackInsightResponse;
import com.bobfull.restaurantinsight.service.RestaurantFeedbackInsightService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
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

/**
 * 식당 등록·내 식당 목록/상세·수정·삭제 API의 인증·인가와 응답을 검증한다.
 */
@WebMvcTest(controllers = OwnerRestaurantController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=owner-restaurant-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class OwnerRestaurantControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RestaurantService restaurantService;

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
    void 인증_없이_식당을_등록하면_401을_반환한다() throws Exception {
        // given
        RestaurantCreateRequest request =
                new RestaurantCreateRequest("밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, null);

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void OWNER_권한이_없는_회원이_식당을_등록하면_403을_반환한다() throws Exception {
        // given
        RestaurantCreateRequest request =
                new RestaurantCreateRequest("밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, null);

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void OWNER가_식당을_등록하면_201과_restaurantId를_반환한다() throws Exception {
        // given
        RestaurantCreateRequest request =
                new RestaurantCreateRequest("밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000, null);
        given(restaurantService.register(1L, request)).willReturn(new RestaurantIdResponse(1L));

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.restaurantId", is(1)));
    }

    @Test
    void 필수값이_비어있으면_식당_등록은_400을_반환한다() throws Exception {
        // given
        String invalidBody = """
                {"name":"","address":"주소","category":"한식","description":"설명","keyword":"키워드","depositPerPerson":10000}
                """;

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 내_식당_목록을_조회하면_페이징_형식으로_반환한다() throws Exception {
        // given
        OwnerRestaurantListResponse item =
                new OwnerRestaurantListResponse(
                        1L, "밥풀식당", "제주시 애월읍 1", "한식", 10000, RestaurantStatus.ACTIVE,
                        "https://image.example");
        PageResponse<OwnerRestaurantListResponse> page = new PageResponse<>(List.of(item), 0, 20, 1, 1);
        given(restaurantService.getMyRestaurants(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .willReturn(page);

        // when
        ResultActions result = mockMvc.perform(
                get("/api/owner/restaurants").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].restaurantId", is(1)))
                .andExpect(jsonPath("$.data.content[0].imageUrl", is("https://image.example")))
                .andExpect(jsonPath("$.data.totalElements", is(1)));
    }

    @Test
    void 내_식당_상세를_조회한다() throws Exception {
        // given
        given(restaurantService.getMyRestaurant(1L, 10L)).willReturn(new OwnerRestaurantDetailResponse(
                10L, "밥풀식당", "제주시 애월읍 1", "한식", "설명", "흑돼지,혼밥", 10000,
                RestaurantStatus.ACTIVE, "https://detail-image.example"));

        // when
        ResultActions result = mockMvc.perform(
                get("/api/owner/restaurants/10").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("ACTIVE")))
                .andExpect(jsonPath("$.data.imageUrl", is("https://detail-image.example")));
    }

    @Test
    void 식당_정보를_수정한다() throws Exception {
        // given
        RestaurantUpdateRequest request = new RestaurantUpdateRequest("새이름", "새설명", "한식,혼밥", 12000, null);
        given(restaurantService.update(1L, 10L, request)).willReturn(new RestaurantIdResponse(10L));

        // when
        ResultActions result = mockMvc.perform(patch("/api/owner/restaurants/10")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantId", is(10)));
    }

    @Test
    void 식당을_삭제한다() throws Exception {
        // given
        given(restaurantService.delete(1L, 10L)).willReturn(new RestaurantIdResponse(10L));

        // when
        ResultActions result = mockMvc.perform(
                delete("/api/owner/restaurants/10").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restaurantId", is(10)));
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
        var now = java.time.Instant.parse("2026-08-16T00:00:00Z");
        var from = now.minus(java.time.Duration.ofDays(7));
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
