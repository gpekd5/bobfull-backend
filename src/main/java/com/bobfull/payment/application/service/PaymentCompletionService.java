package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.exception.PaymentExpiredException;
import com.bobfull.payment.application.port.PortOnePaymentReader;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 외부 결제 검증은 트랜잭션 밖에서 수행하고, 상태 전이는 짧은 잠금 트랜잭션에 위임한다. */
@Service
public class PaymentCompletionService {
    private static final Logger log = LoggerFactory.getLogger(PaymentCompletionService.class);
    private final PaymentRepository paymentRepository;
    private final PortOnePaymentReader portOnePaymentReader;
    private final PaymentCompletionTransactionService transactionService;
    private final Clock clock;
    private final BusinessMetricRecorder businessMetricRecorder;

    public PaymentCompletionService(PaymentRepository paymentRepository, PortOnePaymentReader portOnePaymentReader,
            PaymentCompletionTransactionService transactionService, Clock clock,
            BusinessMetricRecorder businessMetricRecorder) {
        this.paymentRepository = paymentRepository;
        this.portOnePaymentReader = portOnePaymentReader;
        this.transactionService = transactionService;
        this.clock = clock;
        this.businessMetricRecorder = businessMetricRecorder;
    }

    public PaymentCompletionTransactionService.PaymentCompletionResult complete(String paymentId, Long memberId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.isOwnedBy(memberId)) throw new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        return completeVerified(paymentId, payment, memberId);
    }

    public PaymentCompletionTransactionService.PaymentCompletionResult completeFromWebhook(String paymentId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getStatus() == PaymentStatus.PAID) return new PaymentCompletionTransactionService.PaymentCompletionResult(payment, payment.getReservationId(), payment.getReservationParticipantId());
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.EXPIRED) {
            throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        }
        PortOnePaymentReader.PortOnePayment external = portOnePaymentReader.read(paymentId);
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
        if (payment.getStatus() == PaymentStatus.PAID) return new PaymentCompletionTransactionService.PaymentCompletionResult(payment, payment.getReservationId(), payment.getReservationParticipantId());
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.EXPIRED) {
            throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        }
        PortOnePaymentReader.PortOnePayment external = portOnePaymentReader.read(paymentId);
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
