package com.bobfull.chat.application.service;

import com.bobfull.chat.application.port.MemberNamePort;
import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.presentation.response.ChatMessageResponse;
import com.bobfull.chat.presentation.response.ChatMessageSliceResponse;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.entity.MemberRole;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 채팅 참여 권한을 확인하고 메시지 이력을 cursor 기반으로 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ReservationChatAccessPort access;
    private final MemberNamePort names;

    public ChatMessageSliceResponse get(
            Long memberId,
            MemberRole role,
            Long roomId,
            Long cursor,
            int size) {
        if (role != MemberRole.MEMBER) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
        if (cursor != null && cursor <= 0) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }

        ChatRoom room = rooms.findById(roomId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        ReservationChatAccessPort.ChatAccess chatAccess = access.read(room.getReservationId(), memberId);
        if (chatAccess == null || !chatAccess.isActive()) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }

        List<ChatMessage> found = cursor == null
                ? messages.findByChatRoomIdOrderByIdDesc(roomId, PageRequest.of(0, size + 1))
                : messages.findByChatRoomIdAndIdLessThanOrderByIdDesc(
                        roomId, cursor, PageRequest.of(0, size + 1));
        boolean hasNext = found.size() > size;
        List<ChatMessage> page = hasNext ? found.subList(0, size) : found;
        Map<Long, String> namesById = names.readNames(page.stream()
                .map(ChatMessage::getSenderMemberId)
                .collect(Collectors.toSet()));
        List<ChatMessageResponse> content = page.stream()
                .map(message -> ChatMessageResponse.of(
                        message,
                        namesById.get(message.getSenderMemberId()),
                        message.getCreatedAt().atZone(SEOUL).toOffsetDateTime()))
                .toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new ChatMessageSliceResponse(content, nextCursor);
    }
}
