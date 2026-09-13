package com.bobfull.restaurant.sharedtable.application.port;

/**
 * 합석 테이블의 회차·예약 사용 여부를 제공한다.
 */
public interface SharedTableUsagePort {

    boolean hasDiningSession(Long tableId);

    boolean hasActiveReservation(Long tableId);
}
