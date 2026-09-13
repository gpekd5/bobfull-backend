package com.bobfull.payment.application.dto;

import com.bobfull.payment.domain.entity.PaymentPurpose;
import java.math.BigDecimal;

/**
 * 예약 도메인이 검증·계산한 READY Payment 생성값이다.
 * HTTP 요청 DTO가 아니라 예약 도메인과 Payment 서비스 사이의 내부 입력 계약이다.
 */
public record CreateReadyPaymentCommand(
        Long memberId,
        Long timeSlotId,
        Long reservationId,
        PaymentPurpose purpose,
        Integer partySize,
        BigDecimal amount
) {
}
