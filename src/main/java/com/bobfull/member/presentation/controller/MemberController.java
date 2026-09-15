package com.bobfull.member.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.presentation.response.MemberResponse;
import com.bobfull.member.presentation.request.MemberUpdateRequest;
import com.bobfull.member.presentation.response.MemberUpdateResponse;
import com.bobfull.member.application.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/me")
    public ApiResponse<MemberResponse> getMe(@AuthenticationPrincipal AuthMember authMember) {
        return ApiResponse.success(memberService.getMe(authMember.id()));
    }

    @PatchMapping("/me")
    public ApiResponse<MemberUpdateResponse> updateMe(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody MemberUpdateRequest request
    ) {
        return ApiResponse.success(memberService.updateMe(authMember.id(), request));
    }
}
