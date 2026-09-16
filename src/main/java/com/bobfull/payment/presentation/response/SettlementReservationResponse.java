package com.bobfull.payment.presentation.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 예약 회차별 지급 예정 금액 집계 응답이다. */
public record SettlementReservationResponse(
        Long reservationId,
        OffsetDateTime diningSessionAt,
        BigDecimal totalPaidAmount,
        BigDecimal totalRefundedAmount,
        BigDecimal expectedSettlementAmount
) {
}
