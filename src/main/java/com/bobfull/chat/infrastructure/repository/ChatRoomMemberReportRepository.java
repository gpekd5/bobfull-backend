package com.bobfull.chat.infrastructure.repository;

import com.bobfull.chat.domain.entity.ChatRoomMemberReport;
import com.bobfull.chat.domain.entity.ReportStatus;
import com.bobfull.chat.domain.entity.ReviewDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomMemberReportRepository extends JpaRepository<ChatRoomMemberReport, Long> {

    boolean existsByReporterMemberIdAndChatRoomIdAndReportedMemberId(
            Long reporter,
            Long room,
            Long reported);

    Page<ChatRoomMemberReport> findByStatusOrderByCreatedAtDesc(
            ReportStatus status,
            Pageable pageable);

    long countByReportedMemberIdAndStatus(Long memberId, ReportStatus status);

    long countByReportedMemberIdAndDecision(Long memberId, ReviewDecision decision);
}
