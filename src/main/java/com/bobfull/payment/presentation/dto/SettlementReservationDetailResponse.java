package com.bobfull.payment.presentation.dto;

import java.math.BigDecimal;
import java.util.List;

/** 예약별 결제·환불 이력과 지급 예정 금액을 함께 제공하는 상세 응답이다. */
public record SettlementReservationDetailResponse(
        Long reservationId,
        BigDecimal expectedSettlementAmount,
        List<PaymentItem> payments,
        List<RefundItem> refunds
) {
    public record PaymentItem(String paymentId, String paymentStatus, BigDecimal amount) {
    }

    public record RefundItem(Long refundId, String refundStatus, BigDecimal amount) {
    }
}
