package com.bobfull.admin.presentation.response;

import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record AdminRefundListItemResponse(
        Long refundId,
        String paymentId,
        Long memberId,
        Long reservationId,
        BigDecimal amount,
        RefundStatus refundStatus,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static AdminRefundListItemResponse from(Refund refund) {
        return new AdminRefundListItemResponse(
                refund.getId(), refund.getPayment().getPaymentId(), refund.getPayment().getMemberId(),
                refund.getPayment().getReservationId(), refund.getAmount(), refund.getStatus(),
                refund.getRequestedAt() == null ? null : OffsetDateTime.ofInstant(refund.getRequestedAt(), SEOUL),
                refund.getCompletedAt() == null ? null : OffsetDateTime.ofInstant(refund.getCompletedAt(), SEOUL));
    }
}
