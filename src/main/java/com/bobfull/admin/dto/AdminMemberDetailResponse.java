package com.bobfull.admin.dto;

import com.bobfull.member.domain.entity.MemberRole;
import java.time.OffsetDateTime;

public record AdminMemberDetailResponse(
        Long memberId,
        String email,
        String name,
        String phoneNumber,
        MemberRole role,
        long noShowCount,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public static AdminMemberDetailResponse of(AdminMemberResult result, OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        return new AdminMemberDetailResponse(
                result.memberId(), result.email(), result.name(), result.phoneNumber(), result.role(),
                result.noShowCount(), createdAt, deletedAt);
    }
}
