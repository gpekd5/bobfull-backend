package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.dto.AdminMemberNoShowRateResponse;
import com.bobfull.admin.application.model.AdminMemberNoShowRateResult;
import com.bobfull.admin.presentation.dto.AdminOverviewStatisticsResponse;
import com.bobfull.admin.presentation.dto.AdminRestaurantStatisticsResponse;
import com.bobfull.admin.application.model.AdminRestaurantStatisticsResult;
import com.bobfull.admin.infrastructure.query.AdminStatisticsRepository;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADMIN의 운영 지표·통계 조회를 담당한다(Issue #49 §11-9~11-11). */
@Service
public class AdminStatisticsQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<ParticipationStatus> COUNTED_PARTICIPATION_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.NO_SHOW);

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final AdminStatisticsRepository adminStatisticsRepository;

    public AdminStatisticsQueryService(
            ReservationRepository reservationRepository,
            ReservationParticipantRepository reservationParticipantRepository,
            AdminStatisticsRepository adminStatisticsRepository
    ) {
        this.reservationRepository = reservationRepository;
        this.reservationParticipantRepository = reservationParticipantRepository;
        this.adminStatisticsRepository = adminStatisticsRepository;
    }

    @Transactional(readOnly = true)
    public AdminOverviewStatisticsResponse getOverview() {
        long totalReservationCount = reservationRepository.count();
        long confirmedCount = reservationRepository.countByReservationStatus(ReservationStatus.CONFIRMED);
        double confirmationRate = rate(confirmedCount, totalReservationCount);

        long totalParticipationCount = reservationParticipantRepository
                .countByParticipationStatusIn(COUNTED_PARTICIPATION_STATUSES);
        long noShowCount = reservationParticipantRepository.countByParticipationStatus(ParticipationStatus.NO_SHOW);
        double noShowRate = rate(noShowCount, totalParticipationCount);

        return new AdminOverviewStatisticsResponse(totalReservationCount, confirmationRate, noShowRate);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminRestaurantStatisticsResponse> getRestaurantStatistics(
            LocalDate startDate, LocalDate endDate, Pageable pageable
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        Instant startAt = startDate == null ? null : startDate.atStartOfDay(SEOUL_ZONE).toInstant();
        Instant endAt = endDate == null ? null : endDate.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant();

        Page<AdminRestaurantStatisticsResult> results =
                adminStatisticsRepository.aggregateRestaurantStatistics(startAt, endAt, pageable);
        return PageResponse.from(results.map(result -> AdminRestaurantStatisticsResponse.of(
                result, rate(result.confirmedReservationCount(), result.totalReservationCount()))));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMemberNoShowRateResponse> getMemberNoShowRates(Pageable pageable) {
        Page<AdminMemberNoShowRateResult> results = adminStatisticsRepository.aggregateMemberNoShowRates(pageable);
        return PageResponse.from(results.map(result -> AdminMemberNoShowRateResponse.of(
                result, rate(result.noShowCount(), result.totalReservationCount()))));
    }

    private double rate(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return Math.round((numerator * 1000.0) / denominator) / 10.0;
    }
}
