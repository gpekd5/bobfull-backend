package com.bobfull.reservation.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.reservation.application.service.RecruitmentDeadlineCancellationService;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
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
class RecruitmentDeadlineSchedulerTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private RecruitmentDeadlineCancellationService cancellationService;
    @Mock private BusinessMetricRecorder businessMetricRecorder;

    @Test
    void 한_후보_처리_실패후에도_다음_예약을_계속_처리한다() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        given(reservationRepository.findRecruitmentDeadlineCandidateIds(
                eq(RecruitmentStatus.OPEN),
                eq(List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED)),
                eq(now.plusSeconds(7200)),
                any(Pageable.class)))
                .willReturn(List.of(10L, 20L));
        doThrow(new IllegalStateException("db failure")).when(cancellationService).process(10L);
        RecruitmentDeadlineScheduler scheduler = new RecruitmentDeadlineScheduler(
                reservationRepository, cancellationService, Clock.fixed(now, ZoneOffset.UTC), 100,
                businessMetricRecorder);

        scheduler.closeExpiredRecruitments();

        InOrder order = inOrder(cancellationService);
        order.verify(cancellationService).process(10L);
        order.verify(cancellationService).process(20L);
    }
}
