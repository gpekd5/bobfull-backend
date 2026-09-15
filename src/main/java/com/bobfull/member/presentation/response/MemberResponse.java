package com.bobfull.member.presentation.response;

import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.member.domain.entity.Member;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 내 정보 조회 응답이다.
 * businessNumber는 OWNER만 값을 가지며, MEMBER는 null이라 응답에서 생략된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberResponse(
        Long memberId,
        String email,
        String name,
        String phoneNumber,
        MemberRole role,
        String businessNumber
) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getName(),
                member.getPhoneNumber(),
                member.getRole(),
                member.getBusinessNumber()
        );
    }
}
