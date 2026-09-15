package com.bobfull.reservation.application.port;

/**
 * 예약 준비에 필요한 회차·테이블·식당 정보를 제공한다.
 */
public interface ReservationTargetPort {

    ReservationTarget read(Long timeSlotId, boolean lock);

    record ReservationTarget(Long timeSlotId, int tableCapacity, int depositPerPerson) {
    }
}
