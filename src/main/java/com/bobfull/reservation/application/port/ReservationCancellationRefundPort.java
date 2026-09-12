package com.bobfull.reservation.application.port;

import java.util.List;

/**
 * 예약 취소 시 결제 도메인에 환불을 요청하는 outbound port다(Issue #131, ADR 0005).
 * 예약 도메인은 Payment·Refund Repository나 Entity를 직접 참조하지 않고 이 계약으로만
 * 환불 기능을 호출하며, 환불 금액·Payment 조회·Refund 생성·PortOne 요청은 결제 도메인이 결정한다.
 * 실제 Adapter 구현은 #45(결제 환불 실행 및 Refund 상태 관리) 또는 #44 통합 단계에서
 * 결제 패키지에 작성한다 — 이 Issue는 계약만 정의하고 구현하지 않는다.
 */
public interface ReservationCancellationRefundPort {

    /**
     * 취소 대상 참여자들의 Payment 전체 금액에 대한 환불을 요청한다(Issue #44 최종 계약).
     * 호출자({@code ReservationCancellationService.cancel})는 이미 취소 접수 트랜잭션이 커밋되어
     * Reservation·참여자가 CANCELLING/CANCEL_REQUESTED로 저장된 뒤, 트랜잭션 밖에서 이 메서드를
     * 호출한다 — Reservation 행 락을 쥔 채로는 호출되지 않으므로 이 메서드의 실제 구현(Adapter)은
     * 결제 완료 흐름(Payment → Reservation)과의 락 순서 역전을 걱정하지 않아도 된다.
     *
     * <p>이 메서드가 예외를 던지거나 참여자별 환불 중 일부만 성공해도 이미 커밋된
     * CANCELLING/CANCEL_REQUESTED 상태는 롤백되지 않는다. 각 참여자의 실제 CANCELLED 확정은
     * 환불이 개별적으로 완료될 때마다 결제 도메인의 공통 완료 경로({@code RefundCompletionService})가
     * 자신이 소유한 {@code ReservationCancellationCompletionPort}를 통해 예약 도메인의
     * {@code ReservationCancellationCompletionService}를 호출해 이뤄지며(V2, #45), 완료되지 않은 채
     * 남은 건은 #141의 정합성 확인 스케줄러가 재확인한다.</p>
     */
    List<RefundRequestResult> requestRefunds(RefundRequestCommand command);

    /**
     * @param reservationId              환불 대상이 속한 예약
     * @param reservationParticipantIds  환불 대상 참여자 식별자 목록(추가 참여자 취소는 1건,
     *                                    최초 예약자 취소는 유효 참여자 전체)
     * @param requesterMemberId          취소를 요청한 인증 회원
     * @param cancelReason               취소 사유(각 참여자 Payment에 동일하게 적용)
     */
    record RefundRequestCommand(
            Long reservationId,
            List<Long> reservationParticipantIds,
            Long requesterMemberId,
            String cancelReason
    ) {
    }

    /**
     * @param reservationParticipantId 환불이 요청된 참여자
     * @param refundStatus             결제 도메인이 정의하는 환불 요청 상태 문자열(예: "REQUESTED")
     */
    record RefundRequestResult(Long reservationParticipantId, String refundStatus) {
    }
}
