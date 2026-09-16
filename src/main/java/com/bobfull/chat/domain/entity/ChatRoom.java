package com.bobfull.chat.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 최초 예약 결제 완료 후 예약마다 하나만 생성되는 채팅방이다.
@Entity
@Table(name = "chat_room", uniqueConstraints = @UniqueConstraint(name = "uk_chat_room_reservation", columnNames = "reservation_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private Long reservationId;

    private ChatRoom(Long reservationId) {
        this.reservationId = reservationId;
    }

    public static ChatRoom create(Long reservationId) {
        return new ChatRoom(reservationId);
    }
}
