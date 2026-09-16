package com.bobfull.chat.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 채팅 상대에 대한 사용자 신고와 관리자 최종 판단을 기록한다.
@Entity
@Table(
        name = "chat_room_member_report",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_room_member_report_reporter_room_target",
                columnNames = {"reporter_member_id", "chat_room_id", "reported_member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomMemberReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_member_report_id")
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "chat_room_id", nullable = false, updatable = false)
    private Long chatRoomId;

    @Column(name = "reporter_member_id", nullable = false, updatable = false)
    private Long reporterMemberId;

    @Column(name = "reported_member_id", nullable = false, updatable = false)
    private Long reportedMemberId;

    @Column(name = "anchor_message_id", updatable = false)
    private Long anchorMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReportReason reason;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ReviewDecision decision;

    @Column(name = "reviewed_by_member_id")
    private Long reviewedByMemberId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    private ChatRoomMemberReport(
            Long room,
            Long reporter,
            Long reported,
            Long anchor,
            ReportReason reason,
            String detail) {
        this.chatRoomId = room;
        this.reporterMemberId = reporter;
        this.reportedMemberId = reported;
        this.anchorMessageId = anchor;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING;
    }

    public static ChatRoomMemberReport create(
            Long room,
            Long reporter,
            Long reported,
            Long anchor,
            ReportReason reason,
            String detail) {
        return new ChatRoomMemberReport(room, reporter, reported, anchor, reason, detail);
    }

    public void review(ReviewDecision decision, Long reviewer, Instant at) {
        if (status != ReportStatus.PENDING) {
            throw new IllegalStateException("이미 검토된 신고입니다.");
        }
        this.status = ReportStatus.REVIEWED;
        this.decision = decision;
        this.reviewedByMemberId = reviewer;
        this.reviewedAt = at;
    }
}
