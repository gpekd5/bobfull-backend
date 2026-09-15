package com.bobfull.reservation.infrastructure.scheduler;

import com.bobfull.reservation.application.service.ReservationClosingProcessor;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 식사 종료(TimeSlot.endAt 도달) 대상을 찾아 ReservationStatus를 CONFIRMED에서 CLOSED로
 * 전환한다(Issue #175). 채팅 SEND 차단은 이 스케줄러의 처리 시점과 무관하게
 * {@code ReservationChatAccessReader}가 TimeSlot.endAt을 직접 비교해 즉시 보장하므로, 이
 * 스케줄러가 지연돼도 전송 차단 정책은 깨지지 않는다. 후보별로 독립된 짧은 트랜잭션에서
 * 처리하며, 분산 락은 두지 않는다 — 후보 하나가 이미 처리됐으면
 * {@link ReservationClosingProcessor#close}가 재확인 가드로 멱등 종료한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "reservation.dining-end", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReservationClosingScheduler {

    private final ReservationRepository reservationRepository;
    private final ReservationClosingProcessor processor;
    private final Clock clock;
    private final int batchSize;

    public ReservationClosingScheduler(
            ReservationRepository reservationRepository,
            ReservationClosingProcessor processor,
            Clock clock,
            @Value("${reservation.dining-end.batch-size:100}") int batchSize
    ) {
        this.reservationRepository = reservationRepository;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${reservation.dining-end.fixed-delay:60000}")
    public void closeEndedReservations() {
        reservationRepository.findDiningEndCandidateIds(
                        ReservationStatus.CONFIRMED, clock.instant(), PageRequest.of(0, batchSize))
                .forEach(this::processOne);
    }

    private void processOne(Long reservationId) {
        try {
            processor.close(reservationId);
            log.info("event=RESERVATION_DINING_END_PROCESSED reservationId={}", reservationId);
        } catch (RuntimeException exception) {
            log.error("event=RESERVATION_DINING_END_FAILED reservationId={} reason={}",
                    reservationId, exception.toString(), exception);
        }
    }
}
