package com.bobfull.restaurant.timeslot.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.timeslot.domain.exception.TimeSlotErrorCode;
import com.bobfull.restaurant.timeslot.application.port.TimeSlotReservationUsagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 활성 예약 여부를 기준으로 회차 변경·삭제 가능 여부를 검증한다.
@Service
@RequiredArgsConstructor
public class TimeSlotReservationValidator {

    private final TimeSlotReservationUsagePort reservationUsagePort;

    public void validateChangeAllowed(Long sessionId) {
        if (reservationUsagePort.hasActiveReservation(sessionId)) {
            throw new CustomException(TimeSlotErrorCode.SESSION_HAS_RESERVATION);
        }
    }

    public void validateDeletionAllowed(Long sessionId) {
        validateChangeAllowed(sessionId);
    }
}
