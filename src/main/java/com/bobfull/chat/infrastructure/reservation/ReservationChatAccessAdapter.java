package com.bobfull.chat.infrastructure.reservation;

import com.bobfull.chat.application.port.ReservationChatAccessPort;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
