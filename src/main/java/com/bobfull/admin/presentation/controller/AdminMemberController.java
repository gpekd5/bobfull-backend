package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.presentation.response.AdminMemberDetailResponse;
import com.bobfull.admin.presentation.response.AdminMemberListItemResponse;
import com.bobfull.admin.application.service.AdminMemberQueryService;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final AdminMemberQueryService adminMemberQueryService;

    @GetMapping
    public ApiResponse<PageResponse<AdminMemberListItemResponse>> getMembers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean deleted,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(adminMemberQueryService.getMembers(keyword, role, deleted, pageable));
    }

    @GetMapping("/{memberId}")
    public ApiResponse<AdminMemberDetailResponse> getMember(@PathVariable Long memberId) {
        return ApiResponse.success(adminMemberQueryService.getMember(memberId));
    }
}
