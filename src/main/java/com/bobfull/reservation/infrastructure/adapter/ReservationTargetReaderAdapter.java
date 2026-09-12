package com.bobfull.reservation.infrastructure.adapter;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.reservation.application.port.ReservationTargetReader;
import com.bobfull.restaurant.entity.Restaurant;
import com.bobfull.restaurant.repository.RestaurantRepository;
import com.bobfull.sharedtable.entity.SharedTable;
import com.bobfull.sharedtable.repository.SharedTableRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 예약 준비에 필요한 외부 도메인 조회 경로와 TimeSlot 잠금을 캡슐화한다.
 */
@Component
public class ReservationTargetReaderAdapter implements ReservationTargetReader {

    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;

    public ReservationTargetReaderAdapter(
            TimeSlotRepository timeSlotRepository,
            SharedTableRepository sharedTableRepository,
            RestaurantRepository restaurantRepository
    ) {
        this.timeSlotRepository = timeSlotRepository;
        this.sharedTableRepository = sharedTableRepository;
        this.restaurantRepository = restaurantRepository;
    }

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
