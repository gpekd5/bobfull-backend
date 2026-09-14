package com.bobfull.admin.presentation.dto;
import com.bobfull.chat.domain.entity.*; import java.time.Instant; import java.util.*;
public record AdminModerationReportDetailResponse(Long reportId,Long chatRoomId,ReportReason reason,String detail,Long reporterMemberId,Long reportedMemberId,Long anchorMessageId,Instant createdAt,ReportStatus status,List<ContextMessage> context,ModerationSignals moderationSignals,ReportSignals reportSignals) {
 public record ContextMessage(Long messageId,Long senderMemberId,String content,Instant sentAt,Moderation moderation) { }
 public record Moderation(ModerationProcessingStatus status,Set<ModerationCategory> categories,RiskLevel riskLevel,String promptVersion,String policyVersion,Instant analyzedAt) { }
 public record ModerationSignals(long totalFlaggedCount,long reviewTargetCount,long profanityCount,long personalInformationCount,long spamCount) { }
 public record ReportSignals(long pendingReportCount,long reviewedReportCount,long confirmedViolationCount) { }
}
