package com.bobfull.auth.presentation.dto;

import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.member.domain.entity.Member;

public record SignupResponse(
        Long memberId,
        String email,
        String name,
        MemberRole role
) {
    public static SignupResponse from(Member member) {
        return new SignupResponse(member.getId(), member.getEmail(), member.getName(), member.getRole());
    }
}
