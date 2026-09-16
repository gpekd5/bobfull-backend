package com.bobfull.admin.application.service;

import com.bobfull.admin.application.result.MemberModerationSummaryResult;
import com.bobfull.admin.infrastructure.repository.query.MemberModerationQueryRepository;
import com.bobfull.admin.presentation.request.AdminReportReviewRequest;
import com.bobfull.admin.presentation.response.AdminModerationReportDetailResponse;
import com.bobfull.admin.presentation.response.AdminModerationReportResponse;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ChatRoomMemberReport;
import com.bobfull.chat.domain.entity.ReportStatus;
import com.bobfull.chat.domain.entity.ReviewDecision;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomMemberReportRepository;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 신고 조회와 Human 최종 판단을 처리한다.
@Service
@RequiredArgsConstructor
public class AdminModerationReportService {

    private final ChatRoomMemberReportRepository reports;
    private final ChatMessageRepository messages;
    private final ChatModerationRepository moderations;
    private final MemberModerationQueryRepository memberModerations;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminModerationReportResponse> list(ReportStatus status, Pageable pageable) {
        return PageResponse.from(reports.findByStatusOrderByCreatedAtDesc(
                status == null ? ReportStatus.PENDING : status,
                pageable
        ).map(AdminModerationReportResponse::from));
    }

    // 신고 상세와 주변 메시지, moderation 및 누적 신고 신호를 함께 조회한다.
    @Transactional(readOnly = true)
    public AdminModerationReportDetailResponse get(Long id) {
        ChatRoomMemberReport report = reports.findById(id)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_REPORT_NOT_FOUND));
        MemberModerationSummaryResult summary = memberModerations.findMemberSummary(report.getReportedMemberId())
                .orElse(new MemberModerationSummaryResult(
                        report.getReportedMemberId(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        null
                ));
        return new AdminModerationReportDetailResponse(
                report.getId(),
                report.getChatRoomId(),
                report.getReason(),
                report.getDetail(),
                report.getReporterMemberId(),
                report.getReportedMemberId(),
                report.getAnchorMessageId(),
                report.getCreatedAt(),
                report.getStatus(),
                context(report),
                new AdminModerationReportDetailResponse.ModerationSignals(
                        summary.totalFlaggedCount(),
                        summary.reviewTargetCount(),
                        summary.profanityCount(),
                        summary.personalInformationCount(),
                        summary.spamCount()
                ),
                new AdminModerationReportDetailResponse.ReportSignals(
                        reports.countByReportedMemberIdAndStatus(
                                report.getReportedMemberId(),
                                ReportStatus.PENDING
                        ),
                        reports.countByReportedMemberIdAndStatus(
                                report.getReportedMemberId(),
                                ReportStatus.REVIEWED
                        ),
                        reports.countByReportedMemberIdAndDecision(
                                report.getReportedMemberId(),
                                ReviewDecision.VIOLATION_CONFIRMED
                        )
                )
        );
    }

    // 신고에 Human 판단을 기록하되 회원 제재 상태는 변경하지 않는다.
    @Transactional
    public AdminModerationReportResponse review(Long id, Long admin, AdminReportReviewRequest request) {
        ChatRoomMemberReport report = reports.findById(id)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_REPORT_NOT_FOUND));
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new CustomException(ChatErrorCode.CHAT_ROOM_REPORT_ALREADY_REVIEWED);
        }
        report.review(request.decision(), admin, clock.instant());
        return AdminModerationReportResponse.from(report);
    }

    private List<AdminModerationReportDetailResponse.ContextMessage> context(ChatRoomMemberReport report) {
        List<ChatMessage> all = messages.findByChatRoomIdOrderByIdAsc(report.getChatRoomId());
        List<ChatMessage> selected;
        if (report.getAnchorMessageId() != null) {
            int index = 0;
            while (index < all.size() && !all.get(index).getId().equals(report.getAnchorMessageId())) {
                index++;
            }
            selected = all.subList(Math.max(0, index - 5), Math.min(all.size(), index + 6));
        } else {
            List<ChatMessage> prior = messages
                    .findTop20ByChatRoomIdAndCreatedAtLessThanEqualOrderByIdDesc(
                            report.getChatRoomId(),
                            report.getCreatedAt()
                    );
            Collections.reverse(prior);
            selected = prior;
        }

        Map<Long, ChatModeration> byMessage = moderations.findByMessageIdIn(
                        selected.stream().map(ChatMessage::getId).toList()
                ).stream()
                .collect(Collectors.toMap(ChatModeration::getMessageId, moderation -> moderation));
        return selected.stream()
                .map(message -> {
                    ChatModeration moderation = byMessage.get(message.getId());
                    return new AdminModerationReportDetailResponse.ContextMessage(
                            message.getId(),
                            message.getSenderMemberId(),
                            message.getContent(),
                            message.getCreatedAt(),
                            moderation == null ? null : new AdminModerationReportDetailResponse.Moderation(
                                    moderation.getStatus(),
                                    moderation.getCategories(),
                                    moderation.getRiskLevel(),
                                    moderation.getPromptVersion(),
                                    moderation.getPolicyVersion(),
                                    moderation.getAnalyzedAt()
                            )
                    );
                })
                .toList();
    }
}
