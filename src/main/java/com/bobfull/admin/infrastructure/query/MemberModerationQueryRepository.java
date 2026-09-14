package com.bobfull.admin.infrastructure.query;

import com.bobfull.admin.presentation.dto.AdminMemberModerationEvidenceResponse;
import com.bobfull.admin.presentation.dto.MemberModerationReviewStatus;
import com.bobfull.admin.application.model.MemberModerationSummaryResult;
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
