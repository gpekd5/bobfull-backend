package com.bobfull.chat.application.service;

import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.domain.entity.ChatRoomMemberReport;
import com.bobfull.chat.domain.entity.ReportReason;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomMemberReportRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.presentation.request.ChatRoomMemberReportCreateRequest;
import com.bobfull.chat.presentation.response.ChatRoomMemberReportResponse;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** JWT 신고자와 채팅방 참여 이력을 기준으로 사용자 신고를 생성한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class ChatRoomMemberReportService {

    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ChatRoomMemberReportRepository reports;
    private final ReservationParticipantRepository participants;

    public ChatRoomMemberReportResponse create(
            Long reporter,
            Long roomId,
            Long reported,
            ChatRoomMemberReportCreateRequest request) {
        ChatRoom room = rooms.findById(roomId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        if (reporter.equals(reported)) {
            throw new CustomException(ChatErrorCode.CHAT_ROOM_REPORT_SELF_FORBIDDEN);
        }
        if (request.reason() == ReportReason.OTHER
                && (request.detail() == null || request.detail().isBlank())) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        if (!participants.existsByReservationIdAndMemberId(room.getReservationId(), reporter)
                || !participants.existsByReservationIdAndMemberId(room.getReservationId(), reported)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
        if (reports.existsByReporterMemberIdAndChatRoomIdAndReportedMemberId(
                reporter, roomId, reported)) {
            throw new CustomException(ChatErrorCode.CHAT_ROOM_REPORT_DUPLICATE);
        }
        if (request.anchorMessageId() != null) {
            ChatMessage message = messages.findById(request.anchorMessageId())
                    .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_MESSAGE_ID_NOT_FOUND));
            if (!roomId.equals(message.getChatRoomId())
                    || !reported.equals(message.getSenderMemberId())) {
                throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
            }
        }

        ChatRoomMemberReport report = ChatRoomMemberReport.create(
                roomId,
                reporter,
                reported,
                request.anchorMessageId(),
                request.reason(),
                request.detail());
        return ChatRoomMemberReportResponse.from(reports.save(report));
    }
}
