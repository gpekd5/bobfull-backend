package com.bobfull.restaurant.sharedtable.application.port;

// 테이블 변경 검증에 필요한 회차·예약 사용 여부를 제공한다.
public interface SharedTableUsagePort {

    boolean hasDiningSession(Long tableId);

    boolean hasActiveReservation(Long tableId);
}
