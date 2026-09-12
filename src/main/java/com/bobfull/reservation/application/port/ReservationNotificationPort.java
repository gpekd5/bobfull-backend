package com.bobfull.reservation.application.port;

import java.time.Instant;
import java.util.List;

/**
 * #47 모집 마감 처리 결과(확정·인원 미달 취소)와 결제 완료(접수·참여) 결과를 참여자에게 이메일로
 * 안내하는 outbound port다(Issue #168, ADR 0005). 예약 도메인은 실제 메일 발송 방식을 알지
 * 못하며 이 계약으로만 알림을 요청한다. 모든 메서드는 핵심 트랜잭션(모집 마감 처리 또는 결제
 * 완료)이 커밋된 뒤 AFTER_COMMIT 이벤트 리스너에서만 호출되므로, 이 메서드가 느리거나 실패해도
 * 예약·참여자·결제 상태에는 영향을 주지 않는다.
 */
public interface ReservationNotificationPort {

    void notifyConfirmed(ReservationResultNotification notification);

    void notifyCancelledDueToInsufficientParticipants(ReservationResultNotification notification);

    /** 최초(CREATE) 예약 접수가 완료됐음을 안내한다. 아직 모집 중일 수 있어 "확정"이라 표현하지 않는다. */
    void notifyReservationCreated(ReservationResultNotification notification);

    /** 추가(JOIN) 참여가 완료됐음을 안내한다. 아직 모집 중일 수 있어 "확정"이라 표현하지 않는다. */
    void notifyParticipationCompleted(ReservationResultNotification notification);

    record Recipient(Long memberId, String email, String name) {
    }

    /**
     * @param reservationId    알림 대상 예약
     * @param restaurantName   안내 문구에 포함할 식당명
     * @param restaurantAddress 안내 문구에 포함할 식당 주소
     * @param mealStartAt      안내 문구에 포함할 식사 시작 시각(UTC 저장값, 발송 시 KST로 변환)
     * @param recipients       발송 대상 참여자 목록(유효 참여자만)
     */
    record ReservationResultNotification(
            Long reservationId,
            String restaurantName,
            String restaurantAddress,
            Instant mealStartAt,
            List<Recipient> recipients
    ) {
    }
}
