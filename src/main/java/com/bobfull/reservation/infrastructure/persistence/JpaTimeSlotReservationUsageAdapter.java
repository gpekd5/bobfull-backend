package com.bobfull.reservation.infrastructure.persistence;

import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.timeslot.application.port.TimeSlotReservationUsagePort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회차 변경·삭제를 막아야 하는 예약 상태의 정의를 예약 도메인에 유지한 채 회차 변경 정책에
 * 제공한다. {@code CLOSED}(식사 종료로 생명주기가 끝난 예약)도 포함한다(PR #178 리뷰 반영,
 * Issue #175) — 그렇지 않으면 노쇼 처리가 끝나지 않은 회차를 삭제·수정해 이력 조회가 깨지거나,
 * 삭제 후 같은 시간대로 재등록해 이미 끝난 회차를 다시 예약 가능하게 만드는 우회가 생긴다.
 */
@Component
@RequiredArgsConstructor
public class JpaTimeSlotReservationUsageAdapter implements TimeSlotReservationUsagePort {

    private static final List<ReservationStatus> CHANGE_BLOCKING_STATUSES = List.of(
            ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELLING, ReservationStatus.CLOSED);

    private final ReservationRepository reservationRepository;

    @Override
    public boolean hasActiveReservation(Long timeSlotId) {
        return reservationRepository.existsByTimeSlotIdAndReservationStatusIn(timeSlotId, CHANGE_BLOCKING_STATUSES);
    }
}
