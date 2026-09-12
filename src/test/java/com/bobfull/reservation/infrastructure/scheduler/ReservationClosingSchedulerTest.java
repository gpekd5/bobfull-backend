package com.bobfull.reservation.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import com.bobfull.reservation.application.service.ReservationClosingProcessor;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReservationClosingSchedulerTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationClosingProcessor processor;

    @Test
    void 한_후보_처리_실패후에도_다음_예약을_계속_처리한다() {
        Instant now = Instant.parse("2026-08-08T12:00:00Z");
        given(reservationRepository.findDiningEndCandidateIds(
                eq(ReservationStatus.CONFIRMED),
                eq(now),
                any(Pageable.class)))
                .willReturn(List.of(10L, 20L));
        doThrow(new IllegalStateException("db failure")).when(processor).close(10L);
        ReservationClosingScheduler scheduler = new ReservationClosingScheduler(
                reservationRepository, processor, Clock.fixed(now, ZoneOffset.UTC), 100);

        scheduler.closeEndedReservations();

        InOrder order = inOrder(processor);
        order.verify(processor).close(10L);
        order.verify(processor).close(20L);
    }
}
