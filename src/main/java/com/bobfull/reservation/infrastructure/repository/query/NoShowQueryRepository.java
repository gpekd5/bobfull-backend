package com.bobfull.reservation.infrastructure.repository.query;

import com.bobfull.reservation.application.result.NoShowCustomerResult;
import com.bobfull.reservation.application.result.NoShowHistoryResult;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** §9-4·§9-5 노쇼 이력·집계 조회를 담당한다(Issue #48). */
public interface NoShowQueryRepository {

    Page<NoShowHistoryResult> findHistoriesByReservationId(Long reservationId, Pageable pageable);

    Page<NoShowCustomerResult> findNoShowCustomers(Long restaurantId, Instant startAt, Instant endAt, Pageable pageable);
}
