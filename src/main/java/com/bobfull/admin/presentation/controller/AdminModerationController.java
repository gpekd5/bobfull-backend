package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.dto.AdminMemberModerationDetailResponse;
import com.bobfull.admin.presentation.dto.AdminMemberModerationListItemResponse;
import com.bobfull.admin.presentation.dto.MemberModerationReviewStatus;
import com.bobfull.admin.application.service.MemberModerationQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 전용 채팅 moderation 회원별 집계 조회 API다. */
@RestController
@RequestMapping("/api/admin/moderation/members")
public class AdminModerationController {

    private final MemberModerationQueryService memberModerationQueryService;

    public AdminModerationController(MemberModerationQueryService memberModerationQueryService) {
        this.memberModerationQueryService = memberModerationQueryService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminMemberModerationListItemResponse>> getMembers(
            @RequestParam(required = false) MemberModerationReviewStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(memberModerationQueryService.getMemberModerations(status, pageable));
    }

    @GetMapping("/{memberId}")
    public ApiResponse<AdminMemberModerationDetailResponse> getMember(@PathVariable Long memberId) {
        return ApiResponse.success(memberModerationQueryService.getMemberModeration(memberId));
    }
}
