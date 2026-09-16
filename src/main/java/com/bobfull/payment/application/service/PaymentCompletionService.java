package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.exception.PaymentExpiredException;
import com.bobfull.payment.application.port.PortOnePaymentPort;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// PortOne 결제 결과를 검증하고 내부 결제·예약 확정 흐름을 시작한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCompletionService {

    private final PaymentRepository paymentRepository;
    private final PortOnePaymentPort portOnePaymentPort;
    private final PaymentCompletionTransactionService transactionService;
    private final Clock clock;
    private final BusinessMetricRecorder businessMetricRecorder;

    // 결제 소유권과 외부 결제 결과를 검증한 뒤 내부 상태를 확정한다.
    public PaymentCompletionTransactionService.PaymentCompletionResult complete(String paymentId, Long memberId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.isOwnedBy(memberId)) {
            throw new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        }
        return completeVerified(paymentId, payment, memberId);
    }

    // 검증된 웹훅의 결제 결과를 멱등하게 확인하고 내부 상태를 완료 처리한다.
    public PaymentCompletionTransactionService.PaymentCompletionResult completeFromWebhook(String paymentId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.PAID) {
            return new PaymentCompletionTransactionService.PaymentCompletionResult(
                    payment,
                    payment.getReservationId(),
                    payment.getReservationParticipantId()
            );
        }
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.EXPIRED) {
            throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        }
        PortOnePaymentPort.PortOnePayment external = portOnePaymentPort.read(paymentId);
        if (!paymentId.equals(external.paymentId()) || !external.paid() || external.amount() == null
                || payment.getAmount().compareTo(external.amount()) != 0
                || !Payment.CURRENCY_KRW.equals(external.currency()) || !payment.getCurrency().equals(external.currency())) {
            log.warn("event=PAYMENT_VERIFICATION_INCONCLUSIVE paymentId={} reason=PORTONE_PAYMENT_MISMATCH",
                    paymentId);
            throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        }
        return completeAfterExternalPaid(paymentId, null);
    }

    private PaymentCompletionTransactionService.PaymentCompletionResult completeVerified(String paymentId, Payment payment, Long memberId) {
        if (payment.getStatus() == PaymentStatus.PAID) {
            return new PaymentCompletionTransactionService.PaymentCompletionResult(
                    payment,
                    payment.getReservationId(),
                    payment.getReservationParticipantId()
            );
        }
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.EXPIRED) {
            throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        }
        PortOnePaymentPort.PortOnePayment external = portOnePaymentPort.read(paymentId);
        if (!paymentId.equals(external.paymentId()) || !external.paid() || external.amount() == null
                || payment.getAmount().compareTo(external.amount()) != 0
                || !Payment.CURRENCY_KRW.equals(external.currency()) || !payment.getCurrency().equals(external.currency())) {
            log.warn("event=PAYMENT_VERIFICATION_INCONCLUSIVE paymentId={} reason=PORTONE_PAYMENT_MISMATCH",
                    paymentId);
            throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        }
        return completeAfterExternalPaid(paymentId, memberId);
    }

    private PaymentCompletionTransactionService.PaymentCompletionResult completeAfterExternalPaid(String paymentId, Long memberId) {
        // 외부 결제가 완료된 뒤 내부 확정에 실패하면 자동 롤백할 수 없어 보상 처리가 필요하다.
        try {
            return memberId == null ? transactionService.complete(paymentId) : transactionService.complete(paymentId, memberId);
        } catch (PaymentExpiredException exception) {
            log.error("event=PAYMENT_COMPENSATION_REQUIRED paymentId={} externalStatus={} internalStatus={} expiresAt={} reason={}",
                    paymentId, "PAID", exception.getInternalStatus(), exception.getExpiresAt(),
                    exception.getErrorCode().getCode(), exception);
            businessMetricRecorder.increment(BusinessMetricEvent.PAYMENT_COMPENSATION_REQUIRED);
            throw exception;
        } catch (CustomException exception) {
            log.error("event=PAYMENT_COMPENSATION_REQUIRED paymentId={} externalStatus=PAID internalStatus=UNKNOWN reason={}",
                    paymentId, exception.getErrorCode().getCode(), exception);
            businessMetricRecorder.increment(BusinessMetricEvent.PAYMENT_COMPENSATION_REQUIRED);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("event=PAYMENT_COMPENSATION_REQUIRED paymentId={} externalStatus=PAID internalStatus=UNKNOWN reason={}",
                    paymentId, exception.getClass().getSimpleName(), exception);
            businessMetricRecorder.increment(BusinessMetricEvent.PAYMENT_COMPENSATION_REQUIRED);
            throw exception;
        }
    }
}
