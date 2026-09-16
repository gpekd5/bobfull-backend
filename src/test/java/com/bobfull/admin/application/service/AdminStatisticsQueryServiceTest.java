package com.bobfull.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bobfull.admin.application.result.AdminMemberNoShowRateResult;
import com.bobfull.admin.presentation.response.AdminOverviewStatisticsResponse;
import com.bobfull.admin.application.result.AdminRestaurantStatisticsResult;
import com.bobfull.admin.infrastructure.repository.query.AdminStatisticsRepository;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdminStatisticsQueryServiceTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationParticipantRepository reservationParticipantRepository;
    @Mock private AdminStatisticsRepository adminStatisticsRepository;

    @InjectMocks private AdminStatisticsQueryService service;

    @Test
    void 전체_예약이_없으면_비율은_0이다() {
        given(reservationRepository.count()).willReturn(0L);
        given(reservationRepository.countByReservationStatus(ReservationStatus.CONFIRMED)).willReturn(0L);
        given(reservationParticipantRepository.countByParticipationStatusIn(any())).willReturn(0L);
        given(reservationParticipantRepository.countByParticipationStatus(ParticipationStatus.NO_SHOW)).willReturn(0L);

        AdminOverviewStatisticsResponse response = service.getOverview();

        assertThat(response.totalReservationCount()).isZero();
        assertThat(response.reservationConfirmationRate()).isZero();
        assertThat(response.noShowRate()).isZero();
    }

    @Test
    void 예약_성사율과_노쇼율을_소수점_첫째자리로_반올림한다() {
        given(reservationRepository.count()).willReturn(1000L);
        given(reservationRepository.countByReservationStatus(ReservationStatus.CONFIRMED)).willReturn(780L);
        given(reservationParticipantRepository.countByParticipationStatusIn(any())).willReturn(200L);
        given(reservationParticipantRepository.countByParticipationStatus(ParticipationStatus.NO_SHOW)).willReturn(7L);

        AdminOverviewStatisticsResponse response = service.getOverview();

        assertThat(response.reservationConfirmationRate()).isEqualTo(78.0);
        assertThat(response.noShowRate()).isEqualTo(3.5);
    }

    @Test
    void startDate가_endDate보다_늦으면_400_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20);
        LocalDate startDate = LocalDate.of(2026, 8, 10);
        LocalDate endDate = LocalDate.of(2026, 8, 1);

        Throwable result = catchThrowable(() -> service.getRestaurantStatistics(startDate, endDate, pageable));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 식당별_성사율을_계산한다() {
        Pageable pageable = PageRequest.of(0, 20);
        AdminRestaurantStatisticsResult result = new AdminRestaurantStatisticsResult(1L, "식당", 120L, 90L);
        Page<AdminRestaurantStatisticsResult> page = new PageImpl<>(List.of(result), pageable, 1);
        given(adminStatisticsRepository.aggregateRestaurantStatistics(any(), any(), any())).willReturn(page);

        PageResponse<?> response = service.getRestaurantStatistics(null, null, pageable);

        assertThat(response.content()).hasSize(1);
    }

    @Test
    void 회원_노쇼율이_마스킹된_이름으로_반환된다() {
        Pageable pageable = PageRequest.of(0, 20);
        AdminMemberNoShowRateResult result = new AdminMemberNoShowRateResult(1L, "홍길동", 10L, 2L);
        Page<AdminMemberNoShowRateResult> page = new PageImpl<>(List.of(result), pageable, 1);
        given(adminStatisticsRepository.aggregateMemberNoShowRates(any())).willReturn(page);

        var response = service.getMemberNoShowRates(pageable);

        assertThat(response.content().get(0).name()).isEqualTo("홍○동");
        assertThat(response.content().get(0).noShowRate()).isEqualTo(20.0);
    }
}
