package com.bobfull.restaurant.timeslot.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.TimeSlotErrorCode;
import com.bobfull.restaurant.timeslot.application.port.TimeSlotReservationUsagePort;
import org.springframework.stereotype.Service;

/**
 * 예약 도메인이 연결될 때 회차 변경 가능 여부를 검증하는 경계다.
 */
@Service
public class TimeSlotReservationValidator {

    private final TimeSlotReservationUsagePort reservationUsagePort;

    public TimeSlotReservationValidator(TimeSlotReservationUsagePort reservationUsagePort) {
        this.reservationUsagePort = reservationUsagePort;
    }

    public void validateChangeAllowed(Long sessionId) {
        if (reservationUsagePort.hasActiveReservation(sessionId)) {
            throw new CustomException(TimeSlotErrorCode.SESSION_HAS_RESERVATION);
        }
    }

    public void validateDeletionAllowed(Long sessionId) {
        validateChangeAllowed(sessionId);
    }
}
