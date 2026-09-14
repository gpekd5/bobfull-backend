package com.bobfull.admin.infrastructure.query;

import com.bobfull.admin.application.model.AdminMemberResult;
import com.bobfull.member.domain.entity.MemberRole;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminMemberRepository {

    Page<AdminMemberResult> searchMembers(String keyword, MemberRole role, Boolean deleted, Pageable pageable);

    Optional<AdminMemberResult> findMemberDetail(Long memberId);
}
