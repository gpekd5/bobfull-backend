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

/**
 * 식사 종료 후보 하나를 짧은 트랜잭션에서 CLOSED로 전이한다(Issue #175). Reservation 행 잠금 후
 * 최신 상태와 TimeSlot.endAt을 재확인해, 후보 조회 이후 다른 경로가 이미 처리했거나 아직 식사
 * 시간이 남아 있으면 아무 것도 바꾸지 않고 멱등 종료한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationClosingProcessor {

    private final ReservationRepository reservationRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final Clock clock;

    // 락 순서: Reservation 단독(ADR 0001 "복수 비관적 락의 획득 순서" 참고). TimeSlot은 endAt
    // 재확인만 하므로 락을 걸지 않는다.
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
