package com.bobfull.chat.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.chat.presentation.dto.ChatMessageSliceResponse;
import com.bobfull.chat.presentation.dto.ChatRoomResponse;
import com.bobfull.chat.application.service.ChatMessageQueryService;
import com.bobfull.chat.application.service.ChatRoomQueryService;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.common.exception.ChatErrorCode;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ReservationChatRoomController.class, ChatMessageQueryController.class})
class ChatQueryControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private ChatRoomQueryService chatRoomQueryService;
    @MockitoBean private ChatMessageQueryService chatMessageQueryService;

    @Test
    void 인증된_MEMBER는_예약_채팅방을_공통_응답으로_조회한다() throws Exception {
        // given
        given(chatRoomQueryService.get(1L, MemberRole.MEMBER, 10L)).willReturn(new ChatRoomResponse(3L, 10L));

        // when & then
        mockMvc.perform(get("/api/reservations/10/chat-room").with(authentication(memberAuthentication())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.chatRoomId", is(3)));
    }

    @Test
    void 인증된_MEMBER는_기본_size_50으로_메시지를_조회한다() throws Exception {
        // given
        given(chatMessageQueryService.get(1L, MemberRole.MEMBER, 3L, null, 50))
                .willReturn(new ChatMessageSliceResponse(List.of(), null));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/3/messages").with(authentication(memberAuthentication())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void size가_1보다_작거나_100보다_크면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/3/messages?size=0").with(authentication(memberAuthentication())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
        mockMvc.perform(get("/api/chat/rooms/3/messages?size=101").with(authentication(memberAuthentication())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT_VALUE")));
    }

    @Test
    void size_1과_100은_허용한다() throws Exception {
        given(chatMessageQueryService.get(1L, MemberRole.MEMBER, 3L, null, 1))
                .willReturn(new ChatMessageSliceResponse(List.of(), null));
        given(chatMessageQueryService.get(1L, MemberRole.MEMBER, 3L, null, 100))
                .willReturn(new ChatMessageSliceResponse(List.of(), null));

        mockMvc.perform(get("/api/chat/rooms/3/messages?size=1").with(authentication(memberAuthentication())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/rooms/3/messages?size=100").with(authentication(memberAuthentication())))
                .andExpect(status().isOk());
    }

    @Test
    void 비참여자와_취소된_참여자는_403_ACCESS_DENIED를_반환한다() throws Exception {
        given(chatRoomQueryService.get(1L, MemberRole.MEMBER, 10L))
                .willThrow(new CustomException(CommonErrorCode.ACCESS_DENIED));
        mockMvc.perform(get("/api/reservations/10/chat-room").with(authentication(memberAuthentication())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void 권한은_있지만_채팅방이_없으면_404를_반환한다() throws Exception {
        given(chatRoomQueryService.get(1L, MemberRole.MEMBER, 10L))
                .willThrow(new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        mockMvc.perform(get("/api/reservations/10/chat-room").with(authentication(memberAuthentication())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("CHAT_ROOM_ID_NOT_FOUND")));
    }

    private UsernamePasswordAuthenticationToken memberAuthentication() {
        return new UsernamePasswordAuthenticationToken(new AuthMember(1L, MemberRole.MEMBER), null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
}
