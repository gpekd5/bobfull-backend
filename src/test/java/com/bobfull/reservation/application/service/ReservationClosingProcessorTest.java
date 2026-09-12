package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.timeslot.entity.TimeSlot;
import com.bobfull.timeslot.repository.TimeSlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationClosingProcessorTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private TimeSlotRepository timeSlotRepository;

    private ReservationClosingProcessor processor() {
        return new ReservationClosingProcessor(reservationRepository, timeSlotRepository, CLOCK);
    }

    private Reservation confirmedReservation() {
        Reservation reservation = Reservation.create(200L, 5L);
        ReflectionTestUtils.setField(reservation, "id", 10L);
        reservation.confirm();
        return reservation;
    }

    private TimeSlot timeSlotEndingAt(Instant endAt) {
        TimeSlot timeSlot = TimeSlot.create(300L, endAt.minusSeconds(3600), endAt);
        ReflectionTestUtils.setField(timeSlot, "id", 200L);
        return timeSlot;
    }

    @Test
    void 식사_종료_시각에_도달한_CONFIRMED_예약을_CLOSED로_전이한다() {
        // given
        Reservation reservation = confirmedReservation();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L)).willReturn(Optional.of(timeSlotEndingAt(NOW)));

        // when
        processor().close(10L);

        // then
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CLOSED);
    }

    @Test
    void 이미_CLOSED인_예약은_다시_처리해도_변화가_없다() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.close();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));

        // when
        processor().close(10L);

        // then
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CLOSED);
    }

    @Test
    void CANCELLING_예약은_종료_처리를_덮지_않는다() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.startCancelling();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));

        // when
        processor().close(10L);

        // then
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CANCELLING);
    }

    @Test
    void 아직_식사_종료_시각이_아니면_상태를_바꾸지_않는다() {
        // given
        Reservation reservation = confirmedReservation();
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.of(reservation));
        given(timeSlotRepository.findByIdAndDeletedAtIsNull(200L))
                .willReturn(Optional.of(timeSlotEndingAt(NOW.plusSeconds(1))));

        // when
        processor().close(10L);

        // then
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void 존재하지_않는_예약은_아무_동작도_하지_않는다() {
        // given
        given(reservationRepository.findWithLockById(10L)).willReturn(Optional.empty());

        // when & then (예외 없이 종료)
        processor().close(10L);
    }
}
