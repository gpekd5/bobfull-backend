package com.bobfull.chat.infrastructure.reservation;

import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 예약·참여·회차 정보를 조합해 채팅 접근 가능 상태를 제공한다.
@Component
@RequiredArgsConstructor
public class ReservationChatAccessAdapter implements ReservationChatAccessPort {

    private final ReservationParticipantRepository repository;
    private final ReservationRepository reservationRepository;
    private final TimeSlotRepository timeSlotRepository;

    @Override
    public ChatAccess read(Long reservationId, Long memberId) {
        return reservationRepository.findById(reservationId)
                .flatMap(reservation -> repository.findByReservationIdAndMemberId(reservationId, memberId)
                        .map(participant -> new ChatAccess(
                                participant.getId(),
                                participant.getParticipationStatus(),
                                reservation.getReservationStatus(),
                                timeSlotRepository.findByIdAndDeletedAtIsNull(reservation.getTimeSlotId())
                                        .map(com.bobfull.restaurant.timeslot.domain.entity.TimeSlot::getEndAt)
                                        .orElse(Instant.MIN))))
                .orElse(null);
    }
}
