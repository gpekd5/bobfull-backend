package com.bobfull.reservation.infrastructure.repository.query;

import com.bobfull.reservation.application.result.NoShowCustomerResult;
import com.bobfull.reservation.application.result.NoShowHistoryResult;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

// 예약별 노쇼 이력과 식당별 노쇼 고객 집계를 조회한다.
public interface NoShowQueryRepository {

    Page<NoShowHistoryResult> findHistoriesByReservationId(Long reservationId, Pageable pageable);

    Page<NoShowCustomerResult> findNoShowCustomers(Long restaurantId, Instant startAt, Instant endAt, Pageable pageable);
}
