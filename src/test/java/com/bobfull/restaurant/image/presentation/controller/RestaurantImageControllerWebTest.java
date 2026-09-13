package com.bobfull.restaurant.image.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.restaurant.image.presentation.dto.RestaurantImageUploadUrlRequest;
import com.bobfull.restaurant.image.presentation.dto.RestaurantImageUploadUrlResponse;
import com.bobfull.restaurant.image.application.service.RestaurantImageService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = RestaurantImageController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=restaurant-image-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800",
        "cors.allowed-origins=http://localhost:5173"
})
class RestaurantImageControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RestaurantImageService restaurantImageService;

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
    void OWNER가_식당_이미지_업로드_url을_발급받는다() throws Exception {
        // given
        RestaurantImageUploadUrlRequest request = new RestaurantImageUploadUrlRequest("png", "image/png", 1024L);
        given(restaurantImageService.createUploadUrl(1L, request)).willReturn(new RestaurantImageUploadUrlResponse(
                "https://upload.example",
                "temp/restaurants/1/11111111-1111-1111-1111-111111111111.png",
                "restaurants/1/11111111-1111-1111-1111-111111111111.png"
        ));

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/images/upload-url")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl", is("https://upload.example")))
                .andExpect(jsonPath(
                        "$.data.tempImageKey",
                        is("temp/restaurants/1/11111111-1111-1111-1111-111111111111.png")
                ))
                .andExpect(jsonPath(
                        "$.data.finalImageKey",
                        is("restaurants/1/11111111-1111-1111-1111-111111111111.png")
                ));
    }

    @Test
    void OWNER_권한이_없는_회원은_업로드_url을_발급받을_수_없다() throws Exception {
        // given
        RestaurantImageUploadUrlRequest request = new RestaurantImageUploadUrlRequest("png", "image/png", 1024L);

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/images/upload-url")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }
}
