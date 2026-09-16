package com.bobfull.chat.application.port;

import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;

// 채팅 계층이 예약 참여 상태와 메시지 전송 가능 시간을 조회하는 경계다.
public interface ReservationChatAccessPort {

    ChatAccess read(Long reservationId, Long memberId);

    record ChatAccess(
            Long participantId,
            ParticipationStatus participationStatus,
            ReservationStatus reservationStatus,
            Instant diningEndAt
    ) {
        public ChatAccess(Long participantId, ParticipationStatus participationStatus) {
            this(participantId, participationStatus, ReservationStatus.RECRUITING, Instant.MAX);
        }

        public ChatAccess(
                Long participantId,
                ParticipationStatus participationStatus,
                ReservationStatus reservationStatus
        ) {
            this(participantId, participationStatus, reservationStatus, Instant.MAX);
        }

        public boolean isActive() {
            return participationStatus != ParticipationStatus.CANCELLED;
        }

        // CLOSED 전이 지연과 무관하게 식사 종료 시각부터 신규 메시지를 즉시 차단한다.
        public boolean canSend(Instant now) {
            return isActive()
                    && (reservationStatus == ReservationStatus.RECRUITING
                    || reservationStatus == ReservationStatus.CONFIRMED)
                    && now.isBefore(diningEndAt);
        }
    }
}
