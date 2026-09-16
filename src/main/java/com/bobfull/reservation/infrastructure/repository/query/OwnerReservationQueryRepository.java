package com.bobfull.reservation.infrastructure.repository.query;

import com.bobfull.reservation.application.result.OwnerReservationResult;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OwnerReservationQueryRepository {

    Page<OwnerReservationResult> searchOwnerReservations(
            Long restaurantId,
            ReservationStatus reservationStatus,
            Instant startAt,
            Instant endAt,
            Instant now,
            Pageable pageable);
}
