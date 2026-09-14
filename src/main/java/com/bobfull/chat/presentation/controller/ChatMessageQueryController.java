package com.bobfull.chat.presentation.controller;
import com.bobfull.chat.presentation.dto.ChatMessageSliceResponse;
import com.bobfull.chat.application.service.ChatMessageQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/chat/rooms")
public class ChatMessageQueryController {
    private final ChatMessageQueryService service; public ChatMessageQueryController(ChatMessageQueryService service) { this.service=service; }
    @GetMapping("/{chatRoomId}/messages") public ApiResponse<ChatMessageSliceResponse> get(@AuthenticationPrincipal AuthMember member,@PathVariable Long chatRoomId,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int size) { if(size<1||size>100) throw new com.bobfull.common.exception.CustomException(com.bobfull.common.exception.CommonErrorCode.INVALID_INPUT_VALUE); return ApiResponse.success(service.get(member.id(),member.role(),chatRoomId,cursor,size)); }
}
