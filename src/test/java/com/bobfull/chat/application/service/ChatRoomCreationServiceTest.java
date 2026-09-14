package com.bobfull.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class ChatRoomCreationServiceTest {

    private final ChatRoomRepository rooms = org.mockito.Mockito.mock(ChatRoomRepository.class);
    private final ChatRoomCreationService service = new ChatRoomCreationService(rooms);

    @Test
    void 이미_존재하면_새로_저장하지_않고_그대로_반환한다() {
        // given
        given(rooms.findByReservationId(10L)).willReturn(Optional.of(room(3L, 10L)));

        // when
        ChatRoom result = service.createIfAbsent(10L);

        // then
        assertThat(result.getId()).isEqualTo(3L);
        org.mockito.Mockito.verify(rooms, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void 존재하지_않으면_새로_생성한다() {
        // given
        given(rooms.findByReservationId(10L)).willReturn(Optional.empty());
        given(rooms.save(any(ChatRoom.class))).willReturn(room(3L, 10L));

        // when
        ChatRoom result = service.createIfAbsent(10L);

        // then
        assertThat(result.getId()).isEqualTo(3L);
    }

    @Test
    void 동시_생성으로_UNIQUE_제약에_걸리면_이미_생성된_ChatRoom을_정상_결과로_반환한다() {
        // given: 조회 시점엔 없었지만 저장 사이 다른 트랜잭션이 먼저 커밋한 경쟁 상황
        given(rooms.findByReservationId(10L))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(room(3L, 10L)));
        given(rooms.save(any(ChatRoom.class))).willThrow(new DataIntegrityViolationException("unique violation"));

        // when
        ChatRoom result = service.createIfAbsent(10L);

        // then
        assertThat(result.getId()).isEqualTo(3L);
    }

    @Test
    void UNIQUE_위반이_아닌_다른_저장_오류는_그대로_전파한다() {
        // given: 저장은 실패했는데 재조회에도 없다면 진짜 다른 오류
        given(rooms.findByReservationId(10L)).willReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("다른 제약 위반");
        given(rooms.save(any(ChatRoom.class))).willThrow(failure);

        // when & then
        assertThatThrownBy(() -> service.createIfAbsent(10L)).isSameAs(failure);
    }

    private ChatRoom room(Long id, Long reservationId) {
        ChatRoom room = ChatRoom.create(reservationId);
        ReflectionTestUtils.setField(room, "id", id);
        return room;
    }
}
