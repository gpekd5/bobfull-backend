package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.presentation.request.ReservationSearchRequest;
import com.bobfull.reservation.presentation.response.ReservationSearchResponse;
import com.bobfull.reservation.application.result.ReservationSearchResult;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 모집 중인 예약을 검색 조건과 남은 좌석 기준으로 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationSearchService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final ReservationRepository reservationRepository;
    private final Clock clock;

    public PageResponse<ReservationSearchResponse> searchReservations(
            ReservationSearchRequest request,
            Pageable pageable
    ) {
        validateSearchRequest(request);
        Page<ReservationSearchResult> results =
                reservationRepository.searchRecruitingReservations(request, clock.instant(), pageable);
        return PageResponse.from(results.map(this::toResponse));
    }

    private void validateSearchRequest(ReservationSearchRequest request) {
        if (request.capacity() != null && request.capacity() <= 0) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        if (request.minimumRemainingSeats() != null && request.minimumRemainingSeats() <= 0) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private ReservationSearchResponse toResponse(ReservationSearchResult result) {
        return ReservationSearchResponse.of(
                result,
                toSeoulOffset(result.startAt()),
                toSeoulOffset(result.endAt())
        );
    }

    private OffsetDateTime toSeoulOffset(Instant instant) {
        return instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }
}
