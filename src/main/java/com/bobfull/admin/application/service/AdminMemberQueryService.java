package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.response.AdminMemberDetailResponse;
import com.bobfull.admin.presentation.response.AdminMemberListItemResponse;
import com.bobfull.admin.application.result.AdminMemberResult;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 회원 목록과 상세 정보를 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    // Fragment 인터페이스를 직접 주입할 때 생기는 중복 Bean을 피하려고 합성된 Repository를 사용한다.
    private final MemberRepository memberRepository;

    public PageResponse<AdminMemberListItemResponse> getMembers(
            String keyword, String role, Boolean deleted, Pageable pageable
    ) {
        MemberRole parsedRole = parseRole(role);
        Page<AdminMemberResult> results = memberRepository.searchMembers(keyword, parsedRole, deleted, pageable);
        return PageResponse.from(results.map(this::toListItem));
    }

    public AdminMemberDetailResponse getMember(Long memberId) {
        AdminMemberResult result = memberRepository.findMemberDetail(memberId)
                .orElseThrow(() -> new CustomException(MemberErrorCode.MEMBER_ID_NOT_FOUND));
        return AdminMemberDetailResponse.of(result, toSeoulOffset(result.createdAt()), toSeoulOffset(result.deletedAt()));
    }

    private MemberRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return MemberRole.valueOf(role);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private AdminMemberListItemResponse toListItem(AdminMemberResult result) {
        return AdminMemberListItemResponse.of(result, toSeoulOffset(result.createdAt()), toSeoulOffset(result.deletedAt()));
    }

    private OffsetDateTime toSeoulOffset(Instant instant) {
        return instant == null ? null : instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }
}
