package com.bobfull.restaurant.timeslot.application.port;

/**
 * 회차에 활성 예약이 있는지 예약 도메인 기준으로 제공한다.
 */
public interface TimeSlotReservationUsagePort {

    boolean hasActiveReservation(Long timeSlotId);
}
