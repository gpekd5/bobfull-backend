package com.bobfull.reservation.infrastructure.persistence;

import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.reservation.application.port.ReservationTargetPort;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 예약 준비에 필요한 회차·테이블·식당 조회와 선택적 TimeSlot 잠금을 제공한다.
@Component
@RequiredArgsConstructor
public class JpaReservationTargetAdapter implements ReservationTargetPort {

    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;

    @Override
    public ReservationTarget read(Long timeSlotId, boolean lock) {
        TimeSlot timeSlot = findTimeSlotOrThrow(timeSlotId, lock);
        SharedTable sharedTable = sharedTableRepository.findByIdAndDeletedAtIsNull(timeSlot.getSharedTableId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(sharedTable.getRestaurantId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
        return new ReservationTarget(timeSlot.getId(), sharedTable.getCapacity(), restaurant.getDepositPerPerson());
    }

    private TimeSlot findTimeSlotOrThrow(Long timeSlotId, boolean lock) {
        Optional<TimeSlot> timeSlot = lock
                ? timeSlotRepository.findWithLockByIdAndDeletedAtIsNull(timeSlotId)
                : timeSlotRepository.findByIdAndDeletedAtIsNull(timeSlotId);
        return timeSlot.orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
    }
}
