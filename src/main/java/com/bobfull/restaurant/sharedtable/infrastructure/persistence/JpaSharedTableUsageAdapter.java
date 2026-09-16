package com.bobfull.restaurant.sharedtable.infrastructure.persistence;

import com.bobfull.restaurant.sharedtable.application.port.SharedTableReservationUsagePort;
import com.bobfull.restaurant.sharedtable.application.port.SharedTableUsagePort;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 테이블에 연결된 회차와 활성 예약 사용 여부를 영속 데이터로 조회한다.
@Component
@RequiredArgsConstructor
public class JpaSharedTableUsageAdapter implements SharedTableUsagePort {

    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableReservationUsagePort reservationUsagePort;

    @Override
    public boolean hasDiningSession(Long tableId) {
        return timeSlotRepository.existsBySharedTableIdAndDeletedAtIsNull(tableId);
    }

    @Override
    public boolean hasActiveReservation(Long tableId) {
        return reservationUsagePort.hasActiveReservation(
                timeSlotRepository.findAllBySharedTableIdAndDeletedAtIsNull(tableId).stream()
                .map(TimeSlot::getId)
                .toList());
    }
}
