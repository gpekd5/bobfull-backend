package com.bobfull.reservation.infrastructure.repository.query;

import com.bobfull.reservation.presentation.request.ReservationSearchRequest;
import com.bobfull.reservation.application.result.ReservationSearchResult;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReservationSearchRepository {

    Page<ReservationSearchResult> searchRecruitingReservations(
            ReservationSearchRequest request,
            Instant now,
            Pageable pageable
    );
}
