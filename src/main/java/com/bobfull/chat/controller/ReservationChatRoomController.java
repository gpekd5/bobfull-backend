package com.bobfull.chat.controller;
import com.bobfull.chat.dto.ChatRoomResponse;
import com.bobfull.chat.service.ChatRoomQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/reservations")
public class ReservationChatRoomController {
    private final ChatRoomQueryService service; public ReservationChatRoomController(ChatRoomQueryService service) { this.service=service; }
    @GetMapping("/{reservationId}/chat-room") public ApiResponse<ChatRoomResponse> get(@AuthenticationPrincipal AuthMember member,@PathVariable Long reservationId) { return ApiResponse.success(service.get(member.id(),member.role(),reservationId)); }
}
