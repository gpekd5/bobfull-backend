package com.bobfull.admin.presentation.response;

import com.bobfull.admin.application.result.AdminMemberResult;
import com.bobfull.member.domain.entity.MemberRole;
import java.time.OffsetDateTime;

public record AdminMemberListItemResponse(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        long noShowCount,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public static AdminMemberListItemResponse of(AdminMemberResult result, OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        return new AdminMemberListItemResponse(
                result.memberId(), result.email(), result.name(), result.role(),
                result.noShowCount(), createdAt, deletedAt);
    }
}
