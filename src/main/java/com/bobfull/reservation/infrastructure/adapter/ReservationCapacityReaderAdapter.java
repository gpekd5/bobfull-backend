package com.bobfull.reservation.infrastructure.adapter;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.reservation.application.port.ReservationCapacityReader;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 예약 확정에서 필요한 회차·테이블 조회 경로를 캡슐화한다.
 */
@Component
public class ReservationCapacityReaderAdapter implements ReservationCapacityReader {

    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;

    public ReservationCapacityReaderAdapter(
            TimeSlotRepository timeSlotRepository,
            SharedTableRepository sharedTableRepository
    ) {
        this.timeSlotRepository = timeSlotRepository;
        this.sharedTableRepository = sharedTableRepository;
    }

    @Override
    public int readTableCapacity(Long timeSlotId) {
        TimeSlot timeSlot = findTimeSlotOrThrow(timeSlotId);
        SharedTable sharedTable = sharedTableRepository.findByIdAndDeletedAtIsNull(timeSlot.getSharedTableId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
        return sharedTable.getCapacity();
    }

    @Override
    public Instant readTimeSlotStartAt(Long timeSlotId) {
        return findTimeSlotOrThrow(timeSlotId).getStartAt();
    }

    private TimeSlot findTimeSlotOrThrow(Long timeSlotId) {
        return timeSlotRepository.findByIdAndDeletedAtIsNull(timeSlotId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
    }
}
