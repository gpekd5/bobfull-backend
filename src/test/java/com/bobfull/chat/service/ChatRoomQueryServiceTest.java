package com.bobfull.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.chat.entity.ChatRoom;
import com.bobfull.chat.port.ReservationChatAccessReader;
import com.bobfull.chat.repository.ChatRoomRepository;
import com.bobfull.common.exception.ChatErrorCode;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.security.MemberRole;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class ChatRoomQueryServiceTest {
    private final ChatRoomRepository rooms = org.mockito.Mockito.mock(ChatRoomRepository.class);
    private final ReservationChatAccessReader access = org.mockito.Mockito.mock(ReservationChatAccessReader.class);
    private final ChatRoomCreationService chatRoomCreationService = org.mockito.Mockito.mock(ChatRoomCreationService.class);
    private final BusinessMetricRecorder businessMetricRecorder = org.mockito.Mockito.mock(BusinessMetricRecorder.class);
    private final ChatRoomQueryService service = new ChatRoomQueryService(
            rooms, access, chatRoomCreationService, businessMetricRecorder);

    @Test
    void 비참여자와_CANCELLED_참여자와_MEMBER가_아닌_역할은_403으로_거부한다() {
        // given
        given(access.read(10L, 1L)).willReturn(null);
        given(access.read(10L, 2L)).willReturn(new ReservationChatAccessReader.ChatAccess(3L, ParticipationStatus.CANCELLED));

        // when & then
        assertAccessDenied(() -> service.get(1L, MemberRole.MEMBER, 10L));
        assertAccessDenied(() -> service.get(2L, MemberRole.MEMBER, 10L));
        assertAccessDenied(() -> service.get(3L, MemberRole.OWNER, 10L));
        org.mockito.Mockito.verifyNoInteractions(chatRoomCreationService);
    }

    @Test
    void ChatRoom이_이미_있으면_그대로_응답하고_생성을_시도하지_않는다() {
        // given
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessReader.ChatAccess(4L, ParticipationStatus.RESERVED));
        given(rooms.findByReservationId(10L)).willReturn(Optional.of(room(3L, 10L)));

        // when
        var response = service.get(7L, MemberRole.MEMBER, 10L);

        // then
        assertThat(response.chatRoomId()).isEqualTo(3L);
        org.mockito.Mockito.verifyNoInteractions(chatRoomCreationService);
    }

    @Test
    void ChatRoom이_없으면_별도_트랜잭션으로_복구_생성한_뒤_응답한다() {
        // given: AFTER_COMMIT 생성이 아직 반영되지 않았거나 실패해 ChatRoom이 누락된 상황
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessReader.ChatAccess(4L, ParticipationStatus.RESERVED));
        given(rooms.findByReservationId(10L)).willReturn(Optional.empty());
        given(chatRoomCreationService.createIfAbsent(10L)).willReturn(room(3L, 10L));

        // when
        var response = service.get(7L, MemberRole.MEMBER, 10L);

        // then
        assertThat(response.chatRoomId()).isEqualTo(3L);
    }

    @Test
    void 복구_생성도_실패하면_CHAT_ROOM_NOT_READY로_응답한다() {
        // given
        given(access.read(10L, 7L)).willReturn(new ReservationChatAccessReader.ChatAccess(4L, ParticipationStatus.RESERVED));
        given(rooms.findByReservationId(10L)).willReturn(Optional.empty());
        given(chatRoomCreationService.createIfAbsent(10L)).willThrow(new IllegalStateException("강제 실패(테스트)"));
        Logger logger = (Logger) LoggerFactory.getLogger(ChatRoomQueryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when & then
        try {
            assertThatThrownBy(() -> service.get(7L, MemberRole.MEMBER, 10L))
                    .isInstanceOf(CustomException.class)
                    .extracting(exception -> ((CustomException) exception).getErrorCode())
                    .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_READY);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("event=CHAT_ROOM_CREATION_REQUIRED");
            assertThat(event.getFormattedMessage()).contains("reservationId=10");
            assertThat(event.getFormattedMessage()).contains("attemptSource=QUERY_RECOVERY");
            assertThat(event.getFormattedMessage()).contains("autoRetry=false");
            assertThat(event.getFormattedMessage()).contains("manualActionRequired=true");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }

    private void assertAccessDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    private ChatRoom room(Long id, Long reservationId) {
        ChatRoom room = ChatRoom.create(reservationId);
        ReflectionTestUtils.setField(room, "id", id);
        return room;
    }
}
