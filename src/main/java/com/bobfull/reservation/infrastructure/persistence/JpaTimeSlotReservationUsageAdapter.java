package com.bobfull.reservation.infrastructure.persistence;

import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.timeslot.application.port.TimeSlotReservationUsagePort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 예약 상태를 기준으로 회차 변경·삭제 가능 여부를 제공한다.
@Component
@RequiredArgsConstructor
public class JpaTimeSlotReservationUsageAdapter implements TimeSlotReservationUsagePort {

    // CLOSED도 막아 노쇼 이력 연결을 지키고 종료된 시간대를 재등록하는 우회를 방지한다.
    private static final List<ReservationStatus> CHANGE_BLOCKING_STATUSES = List.of(
            ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELLING, ReservationStatus.CLOSED);

    private final ReservationRepository reservationRepository;

    @Override
    public boolean hasActiveReservation(Long timeSlotId) {
        return reservationRepository.existsByTimeSlotIdAndReservationStatusIn(timeSlotId, CHANGE_BLOCKING_STATUSES);
    }
}
