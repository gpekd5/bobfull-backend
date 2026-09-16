package com.bobfull.reservation.application.port;

import java.util.List;

// 예약 취소 결과를 결제 도메인의 환불 실행 경계로 전달한다.
public interface ReservationCancellationRefundPort {

    // 환불 금액과 Payment·Refund 상태, 외부 결제 요청 방식은 결제 도메인이 결정한다.
    // 취소 상태를 먼저 커밋한 뒤 Reservation 락이 없는 구간에서 외부 환불을 요청한다.
    // 이 순서는 Payment → Reservation 완료 흐름과의 락 순서 역전을 피하고, 환불 실패가 이미 접수된
    // 취소 상태를 되돌리지 않게 한다. 개별 환불 완료는 완료 Port로 확정하며 미완료 건은 정합성
    // 확인 스케줄러가 다시 확인한다(ADR 0005).
    List<RefundRequestResult> requestRefunds(RefundRequestCommand command);

    record RefundRequestCommand(
            Long reservationId,
            List<Long> reservationParticipantIds,
            Long requesterMemberId,
            String cancelReason
    ) {
    }

    record RefundRequestResult(Long reservationParticipantId, String refundStatus) {
    }
}
