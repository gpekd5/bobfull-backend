package com.bobfull.chat.presentation.controller;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.chat.application.service.ChatRoomMemberReportService;
import com.bobfull.chat.presentation.request.ChatRoomMemberReportCreateRequest;
import com.bobfull.chat.presentation.response.ChatRoomMemberReportResponse;
import com.bobfull.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat-rooms/{chatRoomId}/members/{reportedMemberId}/reports")
@RequiredArgsConstructor
public class ChatRoomMemberReportController {

    private final ChatRoomMemberReportService service;

    @PostMapping
    public ApiResponse<ChatRoomMemberReportResponse> create(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long chatRoomId,
            @PathVariable Long reportedMemberId,
            @Valid @RequestBody ChatRoomMemberReportCreateRequest request) {
        return ApiResponse.success(service.create(
                member.id(), chatRoomId, reportedMemberId, request));
    }
}
