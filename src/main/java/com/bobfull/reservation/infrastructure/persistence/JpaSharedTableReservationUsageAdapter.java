package com.bobfull.reservation.infrastructure.persistence;

import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.sharedtable.application.port.SharedTableReservationUsagePort;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 예약 상태를 기준으로 합석 테이블 삭제 가능 여부를 제공한다.
@Component
@RequiredArgsConstructor
public class JpaSharedTableReservationUsageAdapter implements SharedTableReservationUsagePort {

    // CLOSED도 막아 연결된 회차의 노쇼 이력과 소유권 조회 체인을 보존한다.
    private static final List<ReservationStatus> DELETION_BLOCKING_STATUSES = List.of(
            ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELLING, ReservationStatus.CLOSED);

    private final ReservationRepository reservationRepository;

    @Override
    public boolean hasActiveReservation(Collection<Long> timeSlotIds) {
        return !timeSlotIds.isEmpty()
                && reservationRepository.existsByTimeSlotIdInAndReservationStatusIn(timeSlotIds, DELETION_BLOCKING_STATUSES);
    }
}
