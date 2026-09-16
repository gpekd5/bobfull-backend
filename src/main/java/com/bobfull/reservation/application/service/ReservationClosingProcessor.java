package com.bobfull.reservation.application.service;

import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 식사 종료 후보를 잠가 최신 상태를 확인한 뒤 CLOSED로 전이한다.
@Service
@RequiredArgsConstructor
public class ReservationClosingProcessor {

    private final ReservationRepository reservationRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final Clock clock;

    // 후보 조회 뒤 상태가 달라질 수 있어 Reservation을 잠그고 재확인한다.
    // TimeSlot은 종료 시각만 읽으므로 추가 잠금 없이 Reservation 단독 락 순서를 유지한다(ADR 0001).
    @Transactional
    public void close(Long reservationId) {
        Reservation reservation = reservationRepository.findWithLockById(reservationId).orElse(null);
        if (reservation == null || reservation.getReservationStatus() != ReservationStatus.CONFIRMED) {
            return;
        }
        TimeSlot timeSlot = timeSlotRepository.findByIdAndDeletedAtIsNull(reservation.getTimeSlotId()).orElse(null);
        if (timeSlot == null || clock.instant().isBefore(timeSlot.getEndAt())) {
            return;
        }
        reservation.close();
    }
}
