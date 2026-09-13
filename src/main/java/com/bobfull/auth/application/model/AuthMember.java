package com.bobfull.auth.application.model;

import com.bobfull.member.domain.entity.MemberRole;

/**
 * 인증된 회원의 식별자와 역할을 담아 Controller에 전달하는 공통 객체다.
 * 클라이언트가 보낸 값이 아니라 인증 컨텍스트에서만 생성된다.
 */
public record AuthMember(Long id, MemberRole role) {
}
