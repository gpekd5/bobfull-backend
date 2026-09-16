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

// 식사가 끝난 확정 예약을 찾아 CLOSED 전이를 요청한다.
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

    // 후보별 재확인으로 멱등성을 보장하므로 분산 락 없이 짧은 트랜잭션으로 나눠 처리한다.
    // 채팅 전송 차단은 TimeSlot.endAt을 직접 검사해 이 스케줄러 지연과 무관하게 적용된다.
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
