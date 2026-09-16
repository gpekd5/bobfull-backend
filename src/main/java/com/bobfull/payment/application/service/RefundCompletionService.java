package com.bobfull.payment.application.service;

import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.application.port.ReservationCancellationCompletionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 외부 환불 결과와 예약 취소 완료를 하나의 후속 처리 경로로 조율한다.
@Service
@RequiredArgsConstructor
public class RefundCompletionService {
    private final RefundTransactionService transactionService;
    private final ReservationCancellationCompletionPort cancellationCompletionPort;

    // 즉시 환불 응답을 반영하고 완료된 경우 예약 참여 상태까지 함께 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundTransactionService.RefundCompletion reflectExternalResult(
            Long refundId, String cancellationId, boolean completed) {
        RefundTransactionService.RefundCompletion completion =
                transactionService.reflectExternalResult(refundId, cancellationId, completed);
        completeParticipantIfCompleted(completion);
        return completion;
    }

    // Cancelled 웹훅을 반영하고 완료된 경우 예약 참여 상태까지 함께 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeFromWebhook(String paymentId, String cancellationId) {
        transactionService.completeFromWebhook(paymentId, cancellationId)
                .ifPresent(this::completeParticipantIfCompleted);
    }

    public void markProcessingFromWebhook(String paymentId, String cancellationId) {
        transactionService.markProcessingFromWebhook(paymentId, cancellationId);
    }

    private void completeParticipantIfCompleted(RefundTransactionService.RefundCompletion completion) {
        if (completion.refundStatus() != RefundStatus.COMPLETED) {
            return;
        }
        cancellationCompletionPort.complete(
                completion.reservationId(),
                completion.reservationParticipantId(),
                completion.completedAt());
    }
}
