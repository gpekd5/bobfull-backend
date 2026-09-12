package com.bobfull.payment.adapter;

import com.bobfull.payment.port.ReservationCancellationCompletionPort;
import com.bobfull.reservation.application.service.ReservationCancellationCompletionService;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 결제 도메인의 취소 완료 Port를 예약 도메인의 완료 전용 서비스로 연결한다. */
@Component
public class ReservationCancellationCompletionAdapter implements ReservationCancellationCompletionPort {

    private final ReservationCancellationCompletionService completionService;

    public ReservationCancellationCompletionAdapter(
            ReservationCancellationCompletionService completionService
    ) {
        this.completionService = completionService;
    }

    @Override
    public void complete(Long reservationId, Long reservationParticipantId, Instant completedAt) {
        completionService.complete(reservationId, reservationParticipantId, completedAt);
    }
}
