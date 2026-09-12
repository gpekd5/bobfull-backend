package com.bobfull.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bobfull.chat.dto.ChatMessageSliceResponse;
import com.bobfull.chat.entity.ChatMessage;
import com.bobfull.chat.entity.ChatRoom;
import com.bobfull.chat.port.MemberNameReader;
import com.bobfull.chat.port.ReservationChatAccessReader;
import com.bobfull.chat.repository.ChatMessageRepository;
import com.bobfull.chat.repository.ChatRoomRepository;
import com.bobfull.common.security.MemberRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageQueryServiceTest {
    private final ChatRoomRepository rooms = org.mockito.Mockito.mock(ChatRoomRepository.class);
    private final ChatMessageRepository messages = org.mockito.Mockito.mock(ChatMessageRepository.class);
    private final ReservationChatAccessReader access = org.mockito.Mockito.mock(ReservationChatAccessReader.class);
    private final MemberNameReader names = org.mockito.Mockito.mock(MemberNameReader.class);
    private final ChatMessageQueryService service = new ChatMessageQueryService(rooms, messages, access, names);

    @Test
    void 첫_페이지는_size보다_한건_더_조회하고_최신순_size개와_nextCursor를_반환한다() {
        // given
        ChatRoom room = room(1L, 10L);
        ChatMessage m105 = message(105L, 1L, "105");
        ChatMessage m104 = message(104L, 1L, "104");
        ChatMessage m103 = message(103L, 1L, "103");
        given(rooms.findById(1L)).willReturn(java.util.Optional.of(room));
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessReader.ChatAccess(8L, com.bobfull.reservation.domain.entity.ParticipationStatus.RESERVED));
        given(messages.findByChatRoomIdOrderByIdDesc(any(), any())).willReturn(List.of(m105, m104, m103));
        given(names.readNames(any())).willReturn(Map.of(1L, "회원"));

        // when
        ChatMessageSliceResponse result = service.get(7L, MemberRole.MEMBER, 1L, null, 2);

        // then
        assertThat(result.content()).extracting("messageId").containsExactly(105L, 104L);
        assertThat(result.nextCursor()).isEqualTo(104L);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(messages).findByChatRoomIdOrderByIdDesc(org.mockito.ArgumentMatchers.eq(1L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void cursor_다음_마지막_페이지는_작은_ID만_반환하고_nextCursor가_null이다() {
        // given
        given(rooms.findById(1L)).willReturn(java.util.Optional.of(room(1L, 10L)));
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessReader.ChatAccess(8L, com.bobfull.reservation.domain.entity.ParticipationStatus.RESERVED));
        given(messages.findByChatRoomIdAndIdLessThanOrderByIdDesc(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(104L), any()))
                .willReturn(List.of(message(103L, 1L, "103")));
        given(names.readNames(any())).willReturn(Map.of(1L, "회원"));

        // when
        ChatMessageSliceResponse result = service.get(7L, MemberRole.MEMBER, 1L, 104L, 2);

        // then
        assertThat(result.content()).extracting("messageId").containsExactly(103L);
        assertThat(result.nextCursor()).isNull();
    }

    private ChatRoom room(Long id, Long reservationId) {
        ChatRoom room = ChatRoom.create(reservationId);
        ReflectionTestUtils.setField(room, "id", id);
        return room;
    }

    private ChatMessage message(Long id, Long memberId, String content) {
        ChatMessage message = ChatMessage.create(1L, memberId, 8L, content);
        ReflectionTestUtils.setField(message, "id", id);
        ReflectionTestUtils.setField(message, "createdAt", Instant.parse("2026-08-06T00:00:00Z"));
        return message;
    }
}
