package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.presentation.request.ReservationSearchRequest;
import com.bobfull.reservation.presentation.response.ReservationSearchResponse;
import com.bobfull.reservation.application.result.ReservationSearchResult;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReservationSearchServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private ReservationSearchService reservationSearchService;

    @Test
    void 모집중_예약_검색_결과를_계산값과_서울_시간_응답으로_변환한다() {
        // given
        ReservationSearchService service = new ReservationSearchService(reservationRepository, FIXED_CLOCK);
        ReservationSearchRequest request =
                new ReservationSearchRequest("밥풀", LocalDate.of(2026, 8, 1), LocalTime.of(18, 0), 4, 1);
        Pageable pageable = PageRequest.of(0, 20);
        ReservationSearchResult result = new ReservationSearchResult(
                1L,
                10L,
                "밥풀식당",
                100L,
                200L,
                4,
                Instant.parse("2026-08-01T09:00:00Z"),
                Instant.parse("2026-08-01T11:00:00Z"),
                ReservationStatus.RECRUITING,
                RecruitmentStatus.OPEN,
                2L,
                1L
        );
        given(reservationRepository.searchRecruitingReservations(eq(request), eq(FIXED_CLOCK.instant()), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(result), pageable, 1));

        // when
        PageResponse<ReservationSearchResponse> response = service.searchReservations(request, pageable);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).startAt().toString()).isEqualTo("2026-08-01T18:00+09:00");
        assertThat(response.content().get(0).currentParticipantCount()).isEqualTo(2);
        assertThat(response.content().get(0).availableCapacity()).isEqualTo(1);
        assertThat(response.content().get(0).confirmationThreshold()).isEqualTo(3);
    }

    @Test
    void 잘못된_capacity_조건은_INVALID_INPUT_VALUE를_반환한다() {
        // given
        ReservationSearchRequest request = new ReservationSearchRequest(null, null, null, 0, null);

        // when
        Throwable result = catchThrowable(() -> reservationSearchService.searchReservations(
                request, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(reservationRepository);
    }

    @Test
    void 잘못된_minimumRemainingSeats_조건은_INVALID_INPUT_VALUE를_반환한다() {
        // given
        ReservationSearchRequest request = new ReservationSearchRequest(null, null, null, null, 0);

        // when
        Throwable result = catchThrowable(() -> reservationSearchService.searchReservations(
                request, PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(reservationRepository);
    }
}
