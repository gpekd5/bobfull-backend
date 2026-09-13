package com.bobfull.payment.infrastructure.adapter;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.application.port.PortOneRefundRequester;
import com.bobfull.payment.application.service.RefundCompletionService;
import com.bobfull.payment.application.service.RefundTransactionService;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReservationCancellationRefundAdapter implements ReservationCancellationRefundPort {
    private static final Logger log = LoggerFactory.getLogger(ReservationCancellationRefundAdapter.class);
    private final RefundTransactionService transactionService;
    private final RefundCompletionService completionService;
    private final PortOneRefundRequester refundRequester;
    private final BusinessMetricRecorder businessMetricRecorder;

    public ReservationCancellationRefundAdapter(RefundTransactionService transactionService,
            RefundCompletionService completionService, PortOneRefundRequester refundRequester,
            BusinessMetricRecorder businessMetricRecorder) {
        this.transactionService = transactionService;
        this.completionService = completionService;
        this.refundRequester = refundRequester;
        this.businessMetricRecorder = businessMetricRecorder;
    }

    @Override
    public List<RefundRequestResult> requestRefunds(RefundRequestCommand command) {
        // 참여자별 환불은 서로 독립이어야 한다 — 앞선 참여자의 예외가 뒤 참여자의 시도 자체를
        // 막으면 안 된다(순차 Stream.map은 예외 발생 시 나머지 원소를 건너뛴다). 그래서 각
        // 참여자를 개별적으로 시도하고, 실패가 있어도 전원 시도가 끝난 뒤에 대표 예외를 던진다.
        List<RefundRequestResult> results = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (Long participantId : command.reservationParticipantIds().stream().distinct().toList()) {
            try {
                results.add(request(command, participantId));
            } catch (RuntimeException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return results;
    }

    private RefundRequestResult request(RefundRequestCommand command, Long participantId) {
        var preparation = transactionService.createRequested(command.reservationId(), participantId, command.cancelReason());
        Refund refund = preparation.refund();
        if (!preparation.externalCallRequired()) {
            return new RefundRequestResult(participantId, refund.getStatus().name());
        }
        var result = requestFromPortOne(refund);
        try {
            var completion = completionService.reflectExternalResult(refund.getId(), result.cancellationId(), result.completed());
            return new RefundRequestResult(participantId, completion.refundStatus().name());
        } catch (RuntimeException exception) {
            // 예약 완료 반영 실패는 하나의 완료 트랜잭션으로 묶여 있어(Issue #44) Refund·Payment까지
            // 함께 롤백된다. 이 시점에 PortOne이 이미 환불을 완료했다면(completed=true), 롤백으로
            // cancellationId가 DB에서 사라지므로 이 로그가 그 값을 확인할 유일한 단서다 — PortOne이
            // 실제로 실패한 것이 아니므로 PORTONE_REFUND_FAILED로 뭉뚱그리지 않고 재조정이 필요하다는
            // 별도 오류로 구분해, 호출자가 "환불 자체가 실패했다"고 잘못 안내하지 않게 한다.
            log.error("event=REFUND_COMPENSATION_REQUIRED paymentId={} refundId={} cancellationId={} externalStatus={} internalStatus=ROLLBACK autoRetry=false",
                    refund.getPayment().getId(), refund.getId(), result.cancellationId(),
                    result.completed() ? "COMPLETED" : "PROCESSING", exception);
            businessMetricRecorder.increment(BusinessMetricEvent.REFUND_COMPENSATION_REQUIRED);
            if (result.completed()) {
                throw new CustomException(PaymentErrorCode.REFUND_RECONCILIATION_REQUIRED);
            }
            throw new CustomException(PaymentErrorCode.PORTONE_REFUND_FAILED);
        }
    }

    private PortOneRefundRequester.RefundResult requestFromPortOne(Refund refund) {
        try {
            return refundRequester.request(refund.getPayment().getPaymentId(), refund.getAmount(),
                    refund.getRequestReason(), refund.getIdempotencyKey());
        } catch (RuntimeException exception) {
            if (exception instanceof PortOneRefundRequester.ExplicitRefundFailureException) {
                transactionService.markFailed(refund.getId());
                log.warn("event=REFUND_FAILED paymentId={} refundId={} externalStatus=FAILED internalStatus=FAILED autoRetry=false",
                        refund.getPayment().getId(), refund.getId());
            } else {
                log.warn("event=REFUND_RESULT_UNKNOWN paymentId={} refundId={} externalStatus=UNKNOWN internalStatus=REQUESTED autoRetry=false",
                        refund.getPayment().getId(), refund.getId());
            }
            throw new CustomException(PaymentErrorCode.PORTONE_REFUND_FAILED);
        }
    }

}
