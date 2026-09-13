package com.bobfull.chat.adapter;
import com.bobfull.chat.port.ReservationChatAccessReader;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import org.springframework.stereotype.Component;
@Component
public class ReservationChatAccessAdapter implements ReservationChatAccessReader {
    private final ReservationParticipantRepository repository;
    private final ReservationRepository reservationRepository;
    private final TimeSlotRepository timeSlotRepository;
    public ReservationChatAccessAdapter(ReservationParticipantRepository repository, ReservationRepository reservationRepository, TimeSlotRepository timeSlotRepository) { this.repository = repository; this.reservationRepository = reservationRepository; this.timeSlotRepository = timeSlotRepository; }
    public ChatAccess read(Long reservationId, Long memberId) {
        return reservationRepository.findById(reservationId).flatMap(reservation -> repository.findByReservationIdAndMemberId(reservationId, memberId)
                .map(p -> new ChatAccess(p.getId(), p.getParticipationStatus(), reservation.getReservationStatus(),
                        timeSlotRepository.findByIdAndDeletedAtIsNull(reservation.getTimeSlotId())
                                .map(com.bobfull.restaurant.timeslot.domain.entity.TimeSlot::getEndAt).orElse(java.time.Instant.MIN)))).orElse(null);
    }
}
