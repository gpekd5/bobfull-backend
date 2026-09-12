package com.bobfull.admin.repository;

import com.bobfull.admin.dto.AdminReservationResult;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminReservationRepository {

    Page<AdminReservationResult> searchReservations(
            ReservationStatus reservationStatus, Instant startAt, Instant endAt, Pageable pageable);
}
