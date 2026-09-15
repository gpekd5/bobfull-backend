package com.bobfull.chat.presentation.response;

import com.bobfull.chat.domain.entity.ChatRoomMemberReport;
import com.bobfull.chat.domain.entity.ReportReason;
import com.bobfull.chat.domain.entity.ReportStatus;
import java.time.Instant;

public record ChatRoomMemberReportResponse(
        Long reportId,
        Long chatRoomId,
        Long reporterMemberId,
        Long reportedMemberId,
        Long anchorMessageId,
        ReportReason reason,
        String detail,
        ReportStatus status,
        Instant createdAt
) {
    public static ChatRoomMemberReportResponse from(ChatRoomMemberReport report) {
        return new ChatRoomMemberReportResponse(
                report.getId(),
                report.getChatRoomId(),
                report.getReporterMemberId(),
                report.getReportedMemberId(),
                report.getAnchorMessageId(),
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getCreatedAt()
        );
    }
}
