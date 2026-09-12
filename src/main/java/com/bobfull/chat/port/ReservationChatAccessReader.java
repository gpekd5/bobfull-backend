package com.bobfull.chat.port;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;
public interface ReservationChatAccessReader {
    ChatAccess read(Long reservationId, Long memberId);
    record ChatAccess(Long participantId, ParticipationStatus participationStatus, ReservationStatus reservationStatus, Instant diningEndAt) {
        public ChatAccess(Long participantId, ParticipationStatus participationStatus) { this(participantId, participationStatus, ReservationStatus.RECRUITING, Instant.MAX); }
        public ChatAccess(Long participantId, ParticipationStatus participationStatus, ReservationStatus reservationStatus) { this(participantId, participationStatus, reservationStatus, Instant.MAX); }
        public boolean isActive() { return participationStatus != ParticipationStatus.CANCELLED; }
        /**
         * 식사 종료 시각(now >= diningEndAt)부터는 ReservationStatus가 아직 CONFIRMED로 남아
         * 있어도 신규 SEND를 차단한다(Issue #175 Q1). CLOSED 전이 스케줄러의 지연·장애와
         * 무관하게 이 시간 직접 비교가 즉시 정책을 보장한다.
         */
        public boolean canSend(Instant now) { return isActive() && (reservationStatus == ReservationStatus.RECRUITING || reservationStatus == ReservationStatus.CONFIRMED) && now.isBefore(diningEndAt); }
    }
}
