package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bobfull.payment.application.port.PaymentHoldPort;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AvailableCapacityCalculatorTest {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLING);
    private static final List<ReservationStatus> CLOSED_STATUS = List.of(ReservationStatus.CLOSED);
    private static final List<ParticipationStatus> OCCUPYING_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationParticipantRepository reservationParticipantRepository;

    @Mock
    private PaymentHoldPort paymentHoldReader;

    private AvailableCapacityCalculator calculator() {
        return new AvailableCapacityCalculator(reservationRepository, reservationParticipantRepository, paymentHoldReader);
    }

    @Test
    void 활성_예약이_없으면_임시_선점만_차감한다() {
        // given
        given(reservationRepository.findByTimeSlotIdAndReservationStatusIn(200L, ACTIVE_STATUSES))
                .willReturn(Optional.empty());
        given(paymentHoldReader.sumActiveReadyPartySize(200L)).willReturn(1);

        // when
        int availableCapacity = calculator().calculate(200L, 4);

        // then
        assertThat(availableCapacity).isEqualTo(3);
    }

    @Test
    void 활성_예약이_있으면_참여_인원과_임시_선점을_함께_차감한다() {
        // given
        Reservation reservation = reservation(10L);
        given(reservationRepository.findByTimeSlotIdAndReservationStatusIn(200L, ACTIVE_STATUSES))
                .willReturn(Optional.of(reservation));
        given(reservationParticipantRepository.sumPartySizeByStatuses(10L, OCCUPYING_STATUSES)).willReturn(2);
        given(paymentHoldReader.sumActiveReadyPartySize(200L)).willReturn(1);

        // when
        int availableCapacity = calculator().calculate(200L, 4);

        // then
        assertThat(availableCapacity).isEqualTo(1);
    }

    @Test
    void 정원을_초과해_차감되어도_음수를_반환하지_않는다() {
        // given
        Reservation reservation = reservation(10L);
        given(reservationRepository.findByTimeSlotIdAndReservationStatusIn(200L, ACTIVE_STATUSES))
                .willReturn(Optional.of(reservation));
        given(reservationParticipantRepository.sumPartySizeByStatuses(10L, OCCUPYING_STATUSES)).willReturn(4);
        given(paymentHoldReader.sumActiveReadyPartySize(200L)).willReturn(2);

        // when
        int availableCapacity = calculator().calculate(200L, 4);

        // then
        assertThat(availableCapacity).isZero();
    }

    @Test
    void CLOSED_예약이_있으면_참여자_상태와_무관하게_가용_좌석을_0으로_반환한다() {
        // given: 식사 종료(CLOSED)로 생명주기가 끝난 회차는 재예약으로 이어지면 안 된다(Issue #175 회귀 수정)
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(200L, CLOSED_STATUS)).willReturn(true);

        // when
        int availableCapacity = calculator().calculate(200L, 4);

        // then
        assertThat(availableCapacity).isZero();
        verify(reservationRepository, never()).findByTimeSlotIdAndReservationStatusIn(200L, ACTIVE_STATUSES);
        verify(paymentHoldReader, never()).sumActiveReadyPartySize(200L);
    }

    @Test
    void CLOSED_예약의_참여자가_전부_NO_SHOW로_빠져도_좌석은_다시_열리지_않는다() {
        // given: NO_SHOW는 OCCUPYING_STATUSES(RESERVED, CANCEL_REQUESTED)가 아니라 점유 집계에서 빠지지만,
        // CLOSED 자체가 재예약을 막으므로 참여자 집계와 무관하게 0을 반환해야 한다
        given(reservationRepository.existsByTimeSlotIdAndReservationStatusIn(200L, CLOSED_STATUS)).willReturn(true);

        // when
        int availableCapacity = calculator().calculate(200L, 4);

        // then
        assertThat(availableCapacity).isZero();
        verify(reservationParticipantRepository, never()).sumPartySizeByStatuses(any(), any());
    }

    private Reservation reservation(Long id) {
        Reservation reservation = Reservation.create(200L, 1L);
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }
}
