package com.bobfull.admin.application.result;

import com.bobfull.member.domain.entity.MemberRole;
import java.time.Instant;

/** ADMIN 회원 목록·상세 조회의 조회 결과 1건이다(Issue #49). */
public record AdminMemberResult(
        Long memberId,
        String email,
        String name,
        String phoneNumber,
        MemberRole role,
        long noShowCount,
        Instant createdAt,
        Instant deletedAt
) {
}
