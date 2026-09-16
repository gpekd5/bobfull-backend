package com.bobfull.reservation.application.port;

import java.time.Instant;
import java.util.List;

// 예약 처리 결과를 참여자 이메일로 전달하는 외부 알림 경계다.
public interface ReservationNotificationPort {

    // 실제 발송은 핵심 트랜잭션 커밋 뒤 실행되어 실패해도 예약·결제 상태에 영향을 주지 않는다.
    void notifyConfirmed(ReservationResultNotification notification);

    void notifyCancelledDueToInsufficientParticipants(ReservationResultNotification notification);

    // 아직 모집 중일 수 있으므로 최초 예약 결과는 "확정"이 아닌 "접수"로 안내한다.
    void notifyReservationCreated(ReservationResultNotification notification);

    // 아직 모집 중일 수 있으므로 추가 참여 결과도 "확정"으로 표현하지 않는다.
    void notifyParticipationCompleted(ReservationResultNotification notification);

    record Recipient(Long memberId, String email, String name) {
    }

    record ReservationResultNotification(
            Long reservationId,
            String restaurantName,
            String restaurantAddress,
            Instant mealStartAt,
            List<Recipient> recipients
    ) {
    }
}
