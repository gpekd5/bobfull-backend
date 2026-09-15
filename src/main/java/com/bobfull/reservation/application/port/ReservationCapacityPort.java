package com.bobfull.reservation.application.port;

import java.time.Instant;

/**
 * 예약 확정·취소 기준 계산에 필요한 회차 정보를 제공한다.
 */
public interface ReservationCapacityPort {

    int readTableCapacity(Long timeSlotId);

    /**
     * 취소 기한(식사 시작 2시간 전) 검증에 사용하는 회차 시작 시각이다(Issue #131).
     */
    Instant readTimeSlotStartAt(Long timeSlotId);
}
