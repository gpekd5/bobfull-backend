package com.bobfull.restaurant.sharedtable.presentation.controller;

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
import com.bobfull.common.exception.SharedTableErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableIdResponse;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkRequest;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkResponse;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableRequest;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableResponse;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTableStatus;
import com.bobfull.restaurant.sharedtable.application.service.SharedTableService;
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

@WebMvcTest(controllers = SharedTableController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=shared-table-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class SharedTableControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SharedTableService sharedTableService;

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
    void 인증_없이_합석_테이블을_등록하면_401을_반환한다() throws Exception {
        // given
        SharedTableRequest request = new SharedTableRequest(4);

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/10/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void OWNER_권한이_없는_회원이_합석_테이블을_등록하면_403을_반환한다() throws Exception {
        // given
        SharedTableRequest request = new SharedTableRequest(4);

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/10/tables")
                .with(authentication(memberAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void OWNER가_합석_테이블을_등록하면_201과_tableId를_반환한다() throws Exception {
        // given
        SharedTableRequest request = new SharedTableRequest(4);
        given(sharedTableService.register(1L, 10L, request)).willReturn(new SharedTableIdResponse(100L, 1));

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/10/tables")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tableId", is(100)))
                .andExpect(jsonPath("$.data.displayNumber", is(1)));
    }

    @Test
    void OWNER가_합석_테이블을_일괄_등록하면_생성_개수와_표시번호를_반환한다() throws Exception {
        // given
        SharedTableBulkRequest request = new SharedTableBulkRequest(4, 2);
        SharedTableBulkResponse response = new SharedTableBulkResponse(2, List.of(
                new SharedTableResponse(100L, 10L, 1, 4, SharedTableStatus.ACTIVE),
                new SharedTableResponse(101L, 10L, 2, 4, SharedTableStatus.ACTIVE)
        ));
        given(sharedTableService.registerBulk(1L, 10L, request)).willReturn(response);

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/10/tables/bulk")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdTableCount", is(2)))
                .andExpect(jsonPath("$.data.tables[0].displayNumber", is(1)))
                .andExpect(jsonPath("$.data.tables[1].displayNumber", is(2)));
    }

    @Test
    void 일괄_등록_개수가_10개를_초과하면_400을_반환한다() throws Exception {
        // given
        SharedTableBulkRequest request = new SharedTableBulkRequest(4, 11);

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/10/tables/bulk")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void capacity가_없으면_등록은_400을_반환한다() throws Exception {
        // given
        String invalidBody = "{}";

        // when
        ResultActions result = mockMvc.perform(post("/api/owner/restaurants/10/tables")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void 본인_식당의_합석_테이블_목록을_조회하면_페이징_형식으로_반환한다() throws Exception {
        // given
        SharedTableResponse item = new SharedTableResponse(100L, 10L, 1, 4, SharedTableStatus.ACTIVE);
        PageResponse<SharedTableResponse> page = new PageResponse<>(List.of(item), 0, 20, 1, 1);
        given(sharedTableService.getTables(eq(1L), eq(10L), any(Pageable.class))).willReturn(page);

        // when
        ResultActions result = mockMvc.perform(
                get("/api/owner/restaurants/10/tables").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].tableId", is(100)))
                .andExpect(jsonPath("$.data.content[0].displayNumber", is(1)))
                .andExpect(jsonPath("$.data.content[0].status", is("ACTIVE")))
                .andExpect(jsonPath("$.data.totalElements", is(1)));
    }

    @Test
    void 합석_테이블_상세를_조회한다() throws Exception {
        // given
        given(sharedTableService.getTable(1L, 100L))
                .willReturn(new SharedTableResponse(100L, 10L, 1, 6, SharedTableStatus.ACTIVE));

        // when
        ResultActions result = mockMvc.perform(
                get("/api/owner/tables/100").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity", is(6)));
    }

    @Test
    void 합석_테이블_capacity를_수정한다() throws Exception {
        // given
        SharedTableRequest request = new SharedTableRequest(8);
        given(sharedTableService.update(1L, 100L, request)).willReturn(new SharedTableIdResponse(100L, 1));

        // when
        ResultActions result = mockMvc.perform(patch("/api/owner/tables/100")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tableId", is(100)));
    }

    @Test
    void 허용되지_않는_capacity로_수정하면_400을_반환한다() throws Exception {
        // given
        SharedTableRequest request = new SharedTableRequest(3);
        given(sharedTableService.update(1L, 100L, request))
                .willThrow(new CustomException(SharedTableErrorCode.INVALID_TABLE_CAPACITY));

        // when
        ResultActions result = mockMvc.perform(patch("/api/owner/tables/100")
                .with(authentication(ownerAuthentication(1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_TABLE_CAPACITY")));
    }

    @Test
    void 합석_테이블을_삭제한다() throws Exception {
        // given
        given(sharedTableService.delete(1L, 100L)).willReturn(new SharedTableIdResponse(100L, 1));

        // when
        ResultActions result = mockMvc.perform(
                delete("/api/owner/tables/100").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tableId", is(100)));
    }

    @Test
    void 연결된_회차가_있으면_삭제는_409를_반환한다() throws Exception {
        // given
        given(sharedTableService.delete(1L, 100L))
                .willThrow(new CustomException(SharedTableErrorCode.TABLE_HAS_DINING_SESSION));

        // when
        ResultActions result = mockMvc.perform(
                delete("/api/owner/tables/100").with(authentication(ownerAuthentication(1L))));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("TABLE_HAS_DINING_SESSION")));
    }
}
