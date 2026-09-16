package com.bobfull.chat.presentation.response;

import com.bobfull.chat.domain.entity.ChatRoom;

public record ChatRoomResponse(Long chatRoomId, Long reservationId) {
    public static ChatRoomResponse from(ChatRoom room) {
        return new ChatRoomResponse(room.getId(), room.getReservationId());
    }
}
