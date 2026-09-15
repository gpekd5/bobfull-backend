package com.bobfull.chat.presentation.controller;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.chat.application.service.ChatMessageQueryService;
import com.bobfull.chat.presentation.response.ChatMessageSliceResponse;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatMessageQueryController {

    private final ChatMessageQueryService service;

    @GetMapping("/{chatRoomId}/messages")
    public ApiResponse<ChatMessageSliceResponse> get(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long chatRoomId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int size) {
        if (size < 1 || size > 100) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        return ApiResponse.success(service.get(member.id(), member.role(), chatRoomId, cursor, size));
    }
}
