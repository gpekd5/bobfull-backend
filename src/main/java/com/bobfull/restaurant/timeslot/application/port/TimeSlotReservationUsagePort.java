package com.bobfull.restaurant.timeslot.application.port;

// 회차 변경 검증에 필요한 활성 예약 여부를 예약 도메인 경계에서 제공한다.
public interface TimeSlotReservationUsagePort {

    boolean hasActiveReservation(Long timeSlotId);
}
