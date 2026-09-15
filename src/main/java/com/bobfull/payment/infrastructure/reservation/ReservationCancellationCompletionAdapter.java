package com.bobfull.payment.infrastructure.reservation;

import com.bobfull.payment.application.port.ReservationCancellationCompletionPort;
import com.bobfull.reservation.application.service.ReservationCancellationCompletionService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 결제 도메인의 취소 완료 Port를 예약 도메인의 완료 전용 서비스로 연결한다. */
@Component
@RequiredArgsConstructor
public class ReservationCancellationCompletionAdapter implements ReservationCancellationCompletionPort {

    private final ReservationCancellationCompletionService completionService;

    @Override
    public void complete(Long reservationId, Long reservationParticipantId, Instant completedAt) {
        completionService.complete(reservationId, reservationParticipantId, completedAt);
    }
}
