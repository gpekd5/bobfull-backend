package com.bobfull.chat.application.service;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.presentation.dto.*;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.application.port.MemberNameReader;
import com.bobfull.chat.application.port.ReservationChatAccessReader;
import com.bobfull.chat.infrastructure.repository.*;
import com.bobfull.common.exception.*;
import com.bobfull.member.domain.entity.MemberRole;
import java.time.ZoneId;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ChatMessageQueryService {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final ChatRoomRepository rooms; private final ChatMessageRepository messages; private final ReservationChatAccessReader access; private final MemberNameReader names;
    public ChatMessageQueryService(ChatRoomRepository rooms, ChatMessageRepository messages, ReservationChatAccessReader access, MemberNameReader names) { this.rooms=rooms; this.messages=messages; this.access=access; this.names=names; }
    @Transactional(readOnly = true) public ChatMessageSliceResponse get(Long memberId, MemberRole role, Long roomId, Long cursor, int size) {
        if (role != MemberRole.MEMBER) throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        if (cursor != null && cursor <= 0) throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        ChatRoom room=rooms.findById(roomId).orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        ReservationChatAccessReader.ChatAccess a=access.read(room.getReservationId(), memberId);
        if (a == null || !a.isActive()) throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        List<ChatMessage> found=cursor == null ? messages.findByChatRoomIdOrderByIdDesc(roomId, PageRequest.of(0,size+1)) : messages.findByChatRoomIdAndIdLessThanOrderByIdDesc(roomId,cursor,PageRequest.of(0,size+1));
        boolean hasNext=found.size()>size; List<ChatMessage> page=hasNext?found.subList(0,size):found;
        Map<Long,String> namesById=names.readNames(page.stream().map(ChatMessage::getSenderMemberId).collect(java.util.stream.Collectors.toSet()));
        List<ChatMessageResponse> content=page.stream().map(m -> ChatMessageResponse.of(m,namesById.get(m.getSenderMemberId()),m.getCreatedAt().atZone(SEOUL).toOffsetDateTime())).toList();
        return new ChatMessageSliceResponse(content,hasNext?page.get(page.size()-1).getId():null);
    }
}
