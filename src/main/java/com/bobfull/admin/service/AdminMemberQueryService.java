package com.bobfull.admin.service;

import com.bobfull.admin.dto.AdminMemberDetailResponse;
import com.bobfull.admin.dto.AdminMemberListItemResponse;
import com.bobfull.admin.dto.AdminMemberResult;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN의 회원 목록·상세 조회를 담당한다(Issue #49).
 * QueryDSL 커스텀 조회는 {@link MemberRepository}에 합성된 것을 사용한다 — Fragment 인터페이스
 * (AdminMemberRepository) 자체를 주입하면 Spring이 그 구현체를 별도 Bean으로도 등록해
 * NoUniqueBeanDefinitionException이 발생한다.
 */
@Service
public class AdminMemberQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final MemberRepository memberRepository;

    public AdminMemberQueryService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMemberListItemResponse> getMembers(
            String keyword, String role, Boolean deleted, Pageable pageable
    ) {
        MemberRole parsedRole = parseRole(role);
        Page<AdminMemberResult> results = memberRepository.searchMembers(keyword, parsedRole, deleted, pageable);
        return PageResponse.from(results.map(this::toListItem));
    }

    @Transactional(readOnly = true)
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
