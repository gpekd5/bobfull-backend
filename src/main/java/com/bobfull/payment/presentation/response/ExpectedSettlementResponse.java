package com.bobfull.payment.presentation.response;

import java.math.BigDecimal;

// 식당의 결제 완료·환불 완료 이력으로 계산한 지급 예정 금액이다.
public record ExpectedSettlementResponse(
        BigDecimal totalPaidAmount,
        BigDecimal totalRefundedAmount,
        BigDecimal expectedSettlementAmount
) {
}
