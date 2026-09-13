package com.bobfull.common.support;

import com.bobfull.member.domain.entity.MemberRole;

/**
 * @AuthenticationPrincipal로 전달된 AuthMember 값을 검증하기 위한 테스트 전용 응답 DTO다.
 */
public record TestAuthResponse(Long id, MemberRole role) {
}
