package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.dto.AdminMemberModerationDetailResponse;
import com.bobfull.admin.presentation.dto.AdminMemberModerationListItemResponse;
import com.bobfull.admin.presentation.dto.MemberModerationReviewStatus;
import com.bobfull.admin.application.model.MemberModerationSummaryResult;
import com.bobfull.admin.infrastructure.query.MemberModerationQueryRepository;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADMIN이 AI moderation 신호를 회원별로 집계하고 근거 메시지를 조회하는 서비스다. */
@Service
public class MemberModerationQueryService {

    private static final long REVIEW_TARGET_THRESHOLD = 3L;

    private final MemberModerationQueryRepository memberModerationQueryRepository;
    private final MemberRepository memberRepository;

    public MemberModerationQueryService(
            MemberModerationQueryRepository memberModerationQueryRepository, MemberRepository memberRepository) {
        this.memberModerationQueryRepository = memberModerationQueryRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMemberModerationListItemResponse> getMemberModerations(
            MemberModerationReviewStatus reviewStatus, Pageable pageable) {
        Page<AdminMemberModerationListItemResponse> results = memberModerationQueryRepository
                .findMemberSummaries(reviewStatus, pageable)
                .map(this::toListItem);
        return PageResponse.from(results);
    }

    @Transactional(readOnly = true)
    public AdminMemberModerationDetailResponse getMemberModeration(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new CustomException(MemberErrorCode.MEMBER_ID_NOT_FOUND);
        }
        MemberModerationSummaryResult summary = memberModerationQueryRepository.findMemberSummary(memberId)
                .orElse(new MemberModerationSummaryResult(memberId, 0, 0, 0, 0, 0, null));
        return new AdminMemberModerationDetailResponse(memberId, reviewStatus(summary.reviewTargetCount()),
                summary.totalFlaggedCount(), summary.reviewTargetCount(), riskCounts(memberId),
                memberModerationQueryRepository.findFlaggedEvidences(memberId));
    }

    private AdminMemberModerationListItemResponse toListItem(MemberModerationSummaryResult result) {
        return new AdminMemberModerationListItemResponse(result.memberId(), result.profanityCount(),
                result.personalInformationCount(), result.spamCount(), result.totalFlaggedCount(),
                result.reviewTargetCount(), reviewStatus(result.reviewTargetCount()), result.lastFlaggedAt());
    }

    private MemberModerationReviewStatus reviewStatus(long reviewTargetCount) {
        return reviewTargetCount >= REVIEW_TARGET_THRESHOLD
                ? MemberModerationReviewStatus.REVIEW_REQUIRED : MemberModerationReviewStatus.NORMAL;
    }

    private Map<String, Long> riskCounts(Long memberId) {
        Map<RiskLevel, Long> counts = memberModerationQueryRepository.findRiskCounts(memberId);
        Map<String, Long> response = new LinkedHashMap<>();
        response.put("LOW", counts.getOrDefault(RiskLevel.LOW, 0L));
        response.put("MEDIUM", counts.getOrDefault(RiskLevel.MEDIUM, 0L));
        response.put("HIGH", counts.getOrDefault(RiskLevel.HIGH, 0L));
        return response;
    }
}
