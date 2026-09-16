package com.bobfull.admin.infrastructure.repository.query;

import com.bobfull.admin.application.result.AdminReservationResult;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminReservationRepository {

    Page<AdminReservationResult> searchReservations(
            ReservationStatus reservationStatus, Instant startAt, Instant endAt, Pageable pageable);
}
