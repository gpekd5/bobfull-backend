package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.reservation.application.dto.OwnerReservationResult;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OwnerReservationRepository {

    Page<OwnerReservationResult> searchOwnerReservations(
            Long restaurantId,
            ReservationStatus reservationStatus,
            Instant startAt,
            Instant endAt,
            Instant now,
            Pageable pageable);
}
