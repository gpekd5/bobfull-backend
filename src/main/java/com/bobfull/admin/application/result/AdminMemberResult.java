package com.bobfull.admin.application.result;

import com.bobfull.member.domain.entity.MemberRole;
import java.time.Instant;

// 관리자 회원 목록과 상세 조회에 사용하는 결과다.
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
