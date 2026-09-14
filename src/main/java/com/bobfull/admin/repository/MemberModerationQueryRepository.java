package com.bobfull.admin.repository;

import com.bobfull.admin.dto.AdminMemberModerationEvidenceResponse;
import com.bobfull.admin.dto.MemberModerationReviewStatus;
import com.bobfull.admin.dto.MemberModerationSummaryResult;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MemberModerationQueryRepository {

    Page<MemberModerationSummaryResult> findMemberSummaries(
            MemberModerationReviewStatus reviewStatus, Pageable pageable);

    Optional<MemberModerationSummaryResult> findMemberSummary(Long memberId);

    List<AdminMemberModerationEvidenceResponse> findFlaggedEvidences(Long memberId);

    Map<RiskLevel, Long> findRiskCounts(Long memberId);
}
