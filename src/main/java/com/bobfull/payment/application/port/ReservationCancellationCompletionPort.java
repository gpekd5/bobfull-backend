package com.bobfull.payment.application.port;

import java.time.Instant;

// 환불 완료 후 예약 도메인의 참여자·예약 상태를 마무리하기 위한 경계다.
public interface ReservationCancellationCompletionPort {

    void complete(Long reservationId, Long reservationParticipantId, Instant completedAt);
}
