package com.bobfull.admin.presentation.response;

import com.bobfull.chat.domain.entity.ChatRoomMemberReport;
import com.bobfull.chat.domain.entity.ReportReason;
import com.bobfull.chat.domain.entity.ReportStatus;
import com.bobfull.chat.domain.entity.ReviewDecision;
import java.time.Instant;

public record AdminModerationReportResponse(
        Long reportId,
        Long chatRoomId,
        Long reporterMemberId,
        Long reportedMemberId,
        ReportReason reason,
        ReportStatus status,
        Long anchorMessageId,
        Instant createdAt,
        ReviewDecision decision,
        Long reviewedByMemberId,
        Instant reviewedAt
) {

    public static AdminModerationReportResponse from(ChatRoomMemberReport report) {
        return new AdminModerationReportResponse(
                report.getId(),
                report.getChatRoomId(),
                report.getReporterMemberId(),
                report.getReportedMemberId(),
                report.getReason(),
                report.getStatus(),
                report.getAnchorMessageId(),
                report.getCreatedAt(),
                report.getDecision(),
                report.getReviewedByMemberId(),
                report.getReviewedAt()
        );
    }
}
