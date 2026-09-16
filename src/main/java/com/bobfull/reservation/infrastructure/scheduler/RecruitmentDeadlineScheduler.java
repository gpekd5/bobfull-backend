package com.bobfull.reservation.infrastructure.scheduler;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.reservation.application.service.RecruitmentDeadlineCancellationService;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.reservation.application.service.ReservationCancellationTransactionService.RecruitmentDeadlineOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 식사 시작 2시간 전 예약의 모집을 마감하고 성사 기준 미달이면 취소·환불을 시작한다.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "reservation.recruitment-deadline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RecruitmentDeadlineScheduler {
    private static final Duration DEADLINE_OFFSET = Duration.ofHours(2);
    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED);

    private final ReservationRepository reservationRepository;
    private final RecruitmentDeadlineCancellationService cancellationService;
    private final Clock clock;
    private final int batchSize;
    private final BusinessMetricRecorder businessMetricRecorder;

    public RecruitmentDeadlineScheduler(
            ReservationRepository reservationRepository,
            RecruitmentDeadlineCancellationService cancellationService,
            Clock clock,
            @Value("${reservation.recruitment-deadline.batch-size:100}") int batchSize,
            BusinessMetricRecorder businessMetricRecorder
    ) {
        this.reservationRepository = reservationRepository;
        this.cancellationService = cancellationService;
        this.clock = clock;
        this.batchSize = batchSize;
        this.businessMetricRecorder = businessMetricRecorder;
    }

    // 후보별 잠금 재확인으로 멱등성을 보장하므로 분산 락 없이 짧은 트랜잭션으로 나눠 처리한다.
    @Scheduled(fixedDelayString = "${reservation.recruitment-deadline.fixed-delay:60000}")
    public void closeExpiredRecruitments() {
        Instant deadline = clock.instant().plus(DEADLINE_OFFSET);
        reservationRepository.findRecruitmentDeadlineCandidateIds(
                        RecruitmentStatus.OPEN, ACTIVE_STATUSES, deadline, PageRequest.of(0, batchSize))
                .forEach(this::processOne);
    }

    private void processOne(Long reservationId) {
        try {
            RecruitmentDeadlineOutcome outcome = cancellationService.process(reservationId);
            log.info("event=RECRUITMENT_DEADLINE_PROCESSED reservationId={} outcome={}", reservationId, outcome);
        } catch (RuntimeException exception) {
            log.error("event=RECRUITMENT_DEADLINE_FAILED reservationId={} reason={}",
                    reservationId, exception.toString(), exception);
            businessMetricRecorder.increment(BusinessMetricEvent.RECRUITMENT_DEADLINE_FAILED);
        }
    }
}
