package com.bobfull.reservation.application.port;

import java.time.Instant;

// 예약 확정과 취소 기준 계산에 필요한 회차 정보를 제공한다.
public interface ReservationCapacityPort {

    int readTableCapacity(Long timeSlotId);

    // 회원 취소 마감인 식사 시작 2시간 전을 계산하는 기준 시각이다.
    Instant readTimeSlotStartAt(Long timeSlotId);
}
