package com.bobfull.restaurant.sharedtable.application.port;

import java.util.Collection;

/**
 * 합석 테이블에 연결된 회차들의 활성 예약 여부를 예약 도메인 기준으로 제공한다.
 */
public interface SharedTableReservationUsagePort {

    boolean hasActiveReservation(Collection<Long> timeSlotIds);
}
