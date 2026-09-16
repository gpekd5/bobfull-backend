package com.bobfull.chat.presentation.controller;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.chat.application.service.ChatRoomQueryService;
import com.bobfull.chat.presentation.response.ChatRoomResponse;
import com.bobfull.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 예약 참여자가 사용할 채팅방을 조회·복구하는 HTTP 경계를 제공한다.
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationChatRoomController {

    private final ChatRoomQueryService service;

    @GetMapping("/{reservationId}/chat-room")
    public ApiResponse<ChatRoomResponse> get(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long reservationId) {
        return ApiResponse.success(service.get(member.id(), member.role(), reservationId));
    }
}
