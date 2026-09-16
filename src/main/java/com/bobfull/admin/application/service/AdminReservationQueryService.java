package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.response.AdminReservationListItemResponse;
import com.bobfull.admin.application.result.AdminReservationResult;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자용 전체 예약 현황을 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReservationQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    // Fragment 인터페이스를 직접 주입할 때 생기는 중복 Bean을 피하려고 합성된 Repository를 사용한다.
    private final ReservationRepository reservationRepository;

    public PageResponse<AdminReservationListItemResponse> getReservations(
            String reservationStatus, LocalDate startDate, LocalDate endDate, Pageable pageable
    ) {
        ReservationStatus status = parseStatus(reservationStatus);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        Instant startAt = startDate == null ? null : startDate.atStartOfDay(SEOUL_ZONE).toInstant();
        Instant endAt = endDate == null ? null : endDate.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant();

        Page<AdminReservationResult> results =
                reservationRepository.searchReservations(status, startAt, endAt, pageable);
        return PageResponse.from(results.map(result ->
                AdminReservationListItemResponse.of(result, toSeoulOffset(result.startAt()))));
    }

    private ReservationStatus parseStatus(String reservationStatus) {
        if (reservationStatus == null || reservationStatus.isBlank()) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(reservationStatus);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private OffsetDateTime toSeoulOffset(Instant instant) {
        return instant == null ? null : instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }
}
