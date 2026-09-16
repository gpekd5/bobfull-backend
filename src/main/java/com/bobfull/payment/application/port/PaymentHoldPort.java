package com.bobfull.payment.application.port;

import com.bobfull.payment.domain.entity.PaymentPurpose;
import java.util.Collection;
import java.util.Map;

// 예약 도메인이 결제 구현에 의존하지 않고 좌석 임시 선점 현황을 조회하는 계약이다.
public interface PaymentHoldPort {

    boolean existsActiveReadyPayment(Long timeSlotId, PaymentPurpose purpose);

    int sumActiveReadyPartySize(Long timeSlotId);

    // 목록 조회가 회차별 쿼리를 반복하지 않도록 여러 회차의 READY 인원 합계를 한 번에 반환한다.
    // 결과에 없는 회차는 합계가 0인 것으로 취급한다.
    Map<Long, Integer> sumActiveReadyPartySizeByTimeSlotIds(Collection<Long> timeSlotIds);

    // 결제 완료 전에는 참여자가 없으므로 활성 JOIN READY 결제로 중복 결제 준비를 확인한다.
    boolean existsActiveJoinReadyPayment(Long reservationId, Long memberId);
}
