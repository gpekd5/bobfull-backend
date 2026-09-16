package com.bobfull.payment.application.service;

import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.application.port.ReservationCancellationCompletionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 즉시 응답과 웹훅이 같은 예약 완료 경로를 사용하도록 환불 완료를 조정한다. */
@Service
@RequiredArgsConstructor
public class RefundCompletionService {
    private final RefundTransactionService transactionService;
    private final ReservationCancellationCompletionPort cancellationCompletionPort;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundTransactionService.RefundCompletion reflectExternalResult(
            Long refundId, String cancellationId, boolean completed) {
        RefundTransactionService.RefundCompletion completion =
                transactionService.reflectExternalResult(refundId, cancellationId, completed);
        completeParticipantIfCompleted(completion);
        return completion;
    }

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
