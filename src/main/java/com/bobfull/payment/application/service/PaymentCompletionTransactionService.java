package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.exception.PaymentExpiredException;
import com.bobfull.payment.application.port.ReservationConfirmationPort;
import com.bobfull.payment.application.port.ReservationConfirmationPort.ReservationConfirmationResult;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCompletionTransactionService {
    private static final Logger log = LoggerFactory.getLogger(PaymentCompletionTransactionService.class);

    private final PaymentRepository paymentRepository;
    private final ReservationConfirmationPort reservationConfirmationPort;
    private final Clock clock;
    private final BusinessMetricRecorder businessMetricRecorder;

    public PaymentCompletionTransactionService(
            PaymentRepository paymentRepository,
            ReservationConfirmationPort reservationConfirmationPort,
            Clock clock,
            BusinessMetricRecorder businessMetricRecorder
    ) {
        this.paymentRepository = paymentRepository;
        this.reservationConfirmationPort = reservationConfirmationPort;
        this.clock = clock;
        this.businessMetricRecorder = businessMetricRecorder;
    }

    // 락 순서: Payment → Reservation(JOIN 확정 시 ReservationConfirmationService에서 획득).
    // ADR 0001 "복수 비관적 락의 획득 순서" 참고, 역순 금지.
    @Transactional
    public PaymentCompletionResult complete(String paymentId, Long memberId) {
        Payment payment = paymentRepository.findWithLockByPaymentId(paymentId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (memberId != null && !payment.isOwnedBy(memberId)) {
            throw new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED);
        }
        if (payment.getStatus() == PaymentStatus.PAID) {
            return new PaymentCompletionResult(payment, payment.getReservationId(), payment.getReservationParticipantId());
        }
        if (payment.getStatus() == PaymentStatus.EXPIRED) {
            throw new PaymentExpiredException(payment.getStatus(), payment.getExpiresAt());
        }
        if (payment.getStatus() != PaymentStatus.READY) {
            throw new CustomException(PaymentErrorCode.PAYMENT_VERIFICATION_FAILED);
        }

        Instant now = clock.instant();
        if (!payment.getExpiresAt().isAfter(now)) {
            throw new PaymentExpiredException(payment.getStatus(), payment.getExpiresAt());
        }
        payment.complete(now);
        ReservationConfirmationResult result = reservationConfirmationPort.confirm(payment);
        payment.attachReservationConfirmation(result.reservationId(), result.participationId());
        logPaymentCompletedAfterCommit(payment, result);
        return new PaymentCompletionResult(payment, result.reservationId(), result.participationId());
    }

    @Transactional
    public PaymentCompletionResult complete(String paymentId) {
        return complete(paymentId, null);
    }

    private void logPaymentCompletedAfterCommit(Payment payment, ReservationConfirmationResult result) {
        String completedPaymentId = payment.getPaymentId();
        Long completedMemberId = payment.getMemberId();
        Long completedReservationId = result.reservationId();
        Long completedParticipantId = result.participationId();
        BigDecimal completedAmount = payment.getAmount();
        PaymentStatus completedStatus = payment.getStatus();
        AfterCommitExecutor.run(() -> {
            log.info(
                    "event=PAYMENT_COMPLETED paymentId={} memberId={} reservationId={} participantId={} amount={} afterStatus={}",
                    completedPaymentId, completedMemberId, completedReservationId, completedParticipantId,
                    completedAmount, completedStatus);
            businessMetricRecorder.increment(BusinessMetricEvent.PAYMENT_COMPLETED);
        });
    }

    public record PaymentCompletionResult(Payment payment, Long reservationId, Long participationId) { }
}
