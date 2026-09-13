package com.bobfull.payment.presentation.dto;

import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;

public record PaymentCompletionResponse(String paymentId, PaymentStatus paymentStatus, Long reservationId, Long participationId) {
    public static PaymentCompletionResponse from(Payment payment, Long reservationId, Long participationId) {
        if (reservationId == null || participationId == null) {
            throw new IllegalArgumentException("완료 응답의 예약과 참여자 식별자는 필수입니다.");
        }
        return new PaymentCompletionResponse(payment.getPaymentId(), payment.getStatus(), reservationId, participationId);
    }
}
