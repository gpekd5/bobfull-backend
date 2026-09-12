package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.reservation.presentation.dto.MyReservationDetailResponse;
import com.bobfull.reservation.presentation.dto.MyReservationListItemResponse;
import com.bobfull.reservation.application.dto.MyReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MyReservationQueryServiceTest {

    @Mock
    private ReservationParticipantRepository reservationParticipantRepository;

    private MyReservationQueryService service() {
        return new MyReservationQueryService(reservationParticipantRepository);
    }

    private MyReservationResult result() {
        return new MyReservationResult(
                1L, 10L, "밥풀식당", 100L,
                Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2026-08-01T11:00:00Z"),
                ReservationStatus.RECRUITING, RecruitmentStatus.OPEN,
                20L, 2, ParticipationStatus.RESERVED,
                PaymentStatus.PAID, "payment-id-1");
    }

    @Test
    void 내_예약_목록은_서울_시간으로_변환된_페이지_응답을_반환한다() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(reservationParticipantRepository.searchMyReservations(eq(1L), isNull(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(result()), pageable, 1));

        // when
        PageResponse<MyReservationListItemResponse> response = service().getMyReservations(1L, null, pageable);

        // then
        assertThat(response.content()).hasSize(1);
        MyReservationListItemResponse item = response.content().get(0);
        assertThat(item.reservationId()).isEqualTo(1L);
        assertThat(item.startAt().toString()).isEqualTo("2026-08-01T18:00+09:00");
        assertThat(item.paymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void reservationStatus_필터가_유효하지_않으면_400_예외가_발생한다() {
        // when
        Throwable result = catchThrowable(
                () -> service().getMyReservations(1L, "NOT_A_STATUS", PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 내_예약_상세는_서울_시간과_결제ID를_포함해서_반환한다() {
        // given
        given(reservationParticipantRepository.findMyReservationDetail(1L, 5L)).willReturn(Optional.of(result()));

        // when
        MyReservationDetailResponse response = service().getMyReservationDetail(1L, 5L);

        // then
        assertThat(response.reservationId()).isEqualTo(1L);
        assertThat(response.paymentId()).isEqualTo("payment-id-1");
        assertThat(response.startAt().toString()).isEqualTo("2026-08-01T18:00+09:00");
    }

    @Test
    void 본인_참여가_아닌_reservationId_상세_조회는_404_예외가_발생한다() {
        // given
        given(reservationParticipantRepository.findMyReservationDetail(1L, 5L)).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service().getMyReservationDetail(1L, 5L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ReservationErrorCode.RESERVATION_ID_NOT_FOUND);
    }
}
