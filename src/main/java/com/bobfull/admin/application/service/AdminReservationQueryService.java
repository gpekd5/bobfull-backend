package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.dto.AdminReservationListItemResponse;
import com.bobfull.admin.application.model.AdminReservationResult;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN의 전체 예약 현황 조회를 담당한다(Issue #49 §11-5).
 * Fragment 인터페이스(AdminReservationRepository) 대신 합성된 {@link ReservationRepository}를 주입한다
 * (Fragment 인터페이스를 직접 주입하면 Spring이 구현체를 별도 Bean으로도 등록해 중복 Bean 오류가 난다).
 */
@Service
public class AdminReservationQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final ReservationRepository reservationRepository;

    public AdminReservationQueryService(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Transactional(readOnly = true)
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
