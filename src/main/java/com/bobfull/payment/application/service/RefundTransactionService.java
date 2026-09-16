package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.payment.application.port.RefundIdempotencyKeyPort;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 환불 생성과 상태 전이를 비관적 락과 독립 트랜잭션 경계 안에서 확정한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundTransactionService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final Clock clock;
    private final RefundIdempotencyKeyPort idempotencyKeyPort;
    private final BusinessMetricRecorder businessMetricRecorder;

    // 외부 환불 호출 전에 REQUESTED 상태와 멱등성 키를 별도 트랜잭션으로 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundPreparation createRequested(Long reservationId, Long participantId, String cancelReason) {
        Payment payment = paymentRepository.findByReservationIdAndReservationParticipantId(reservationId, participantId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        // Payment 행 락으로 동시 요청을 직렬화한다 — 락 없이 findByPayment_Id만으로 존재 여부를
        // 판단하면 두 트랜잭션이 모두 "없음"으로 보고 saveAndFlush가 payment_id UNIQUE 제약
        // 위반(원시 DB 예외)으로 끝날 수 있다. 락을 먼저 잡으면 뒤 트랜잭션은 앞 트랜잭션의
        // 커밋을 기다린 뒤 이미 생성된 Refund를 보고 REFUND_PROCESSING을 던진다.
        payment = paymentRepository.findWithLockById(payment.getId())
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        var existingRefund = refundRepository.findByPayment_Id(payment.getId());
        if (existingRefund.isPresent()) {
            Refund refund = existingRefund.get();
            if (refund.getStatus() == RefundStatus.COMPLETED) {
                return new RefundPreparation(refund, false);
            }
            if (refund.getStatus() == RefundStatus.PROCESSING || refund.getStatus() == RefundStatus.REQUESTED) {
                throw new CustomException(PaymentErrorCode.REFUND_PROCESSING);
            }
            throw new CustomException(PaymentErrorCode.REFUND_FAILED);
        }
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new CustomException(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE);
        }
        Refund refund = refundRepository.saveAndFlush(
                Refund.create(payment, payment.getAmount(), RefundStatus.REQUESTED, clock.instant(), null,
                        idempotencyKeyPort.generate(), cancelReason));
        log.info("event=REFUND_REQUESTED refundId={} paymentId={} reservationId={} participantId={} amount={} afterStatus={}",
                refund.getId(), payment.getPaymentId(), payment.getReservationId(),
                payment.getReservationParticipantId(), refund.getAmount(), refund.getStatus());
        return new RefundPreparation(refund, true);
    }

    // 외부 환불 응답을 반영하고 완료된 결제와 예약 취소 후속 처리를 위한 결과를 반환한다.
    @Transactional
    public RefundCompletion reflectExternalResult(Long refundId, String cancellationId, boolean completed) {
        // 비관적 락으로 조회한다 — CancelPending/Cancelled 웹훅과 동시에 같은 Refund를 갱신할 때
        // 락 없는 조회는 각자 읽은 스냅샷만으로 판단해 나중에 커밋된 트랜잭션이 앞선 완료 상태를
        // 덮어쓸 수 있다(lost update). 뒤 트랜잭션은 이 락에서 대기했다가 앞 트랜잭션이 남긴 최신
        // 상태를 다시 읽으므로, 아래 엔티티 메서드의 종료 상태 가드가 실제로 적용된다.
        Refund refund = refundRepository.findWithLockById(refundId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.REFUND_ID_NOT_FOUND));
        RefundStatus before = refund.getStatus();
        if (completed) {
            refund.complete(cancellationId, clock.instant());
            if (before == RefundStatus.FAILED) {
                log.warn("event=REFUND_STATE_TRANSITION_BLOCKED refundId={} attempted=COMPLETED currentStatus=FAILED", refundId);
            }
            if (refund.getStatus() == RefundStatus.COMPLETED
                    && refund.getPayment().getStatus() == PaymentStatus.PAID) {
                refund.getPayment().markRefunded();
            }
            if (before != RefundStatus.COMPLETED && refund.getStatus() == RefundStatus.COMPLETED) {
                logRefundCompletedAfterCommit(refund);
            }
        } else {
            refund.markProcessing(cancellationId);
            if (before == RefundStatus.COMPLETED || before == RefundStatus.FAILED) {
                log.warn("event=REFUND_STATE_TRANSITION_BLOCKED refundId={} attempted=PROCESSING currentStatus={}", refundId, before);
            }
        }
        return RefundCompletion.from(refund);
    }

    // PortOne이 명시적으로 거절한 환불을 독립 트랜잭션에서 실패 상태로 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long refundId) {
        Refund refund = refundRepository.findWithLockById(refundId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.REFUND_ID_NOT_FOUND));
        RefundStatus before = refund.getStatus();
        refund.fail();
        if (before == RefundStatus.COMPLETED) {
            log.warn("event=REFUND_STATE_TRANSITION_BLOCKED refundId={} attempted=FAILED currentStatus=COMPLETED", refundId);
        }
    }

    // CancelPending 웹훅을 기존 종료 상태를 역행하지 않도록 반영한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessingFromWebhook(String paymentId, String cancellationId) {
        findRefundForWebhook(paymentId, cancellationId).ifPresent(refund -> {
            RefundStatus before = refund.getStatus();
            refund.markProcessing(cancellationId);
            if (before == RefundStatus.COMPLETED || before == RefundStatus.FAILED) {
                log.warn("event=REFUND_STATE_TRANSITION_BLOCKED refundId={} attempted=PROCESSING currentStatus={}", refund.getId(), before);
            }
        });
    }

    // 외부 PG 상태 확인 이후 환불의 마지막 PG 확인 시각을 갱신한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPgChecked(Long refundId) {
        if (refundRepository.updateLastPgCheckedAt(refundId, clock.instant()) == 0) {
            throw new CustomException(PaymentErrorCode.REFUND_ID_NOT_FOUND);
        }
    }

    // Cancelled 웹훅을 멱등하게 완료 처리하고 예약 취소 후속 처리에 필요한 결과를 반환한다.
    @Transactional
    public java.util.Optional<RefundCompletion> completeFromWebhook(String paymentId, String cancellationId) {
        return findRefundForWebhook(paymentId, cancellationId).map(refund -> {
            RefundStatus before = refund.getStatus();
            refund.complete(cancellationId, clock.instant());
            if (before == RefundStatus.FAILED) {
                log.warn("event=REFUND_STATE_TRANSITION_BLOCKED refundId={} attempted=COMPLETED currentStatus=FAILED", refund.getId());
            }
            if (refund.getStatus() == RefundStatus.COMPLETED
                    && refund.getPayment().getStatus() == PaymentStatus.PAID) {
                refund.getPayment().markRefunded();
            }
            if (before != RefundStatus.COMPLETED && refund.getStatus() == RefundStatus.COMPLETED) {
                logRefundCompletedAfterCommit(refund);
            }
            return RefundCompletion.from(refund);
        });
    }

    // cancellationId가 아직 없는 웹훅을 unique 인덱스로 먼저 잠그면 InnoDB gap lock끼리
    // 교착할 수 있다. 생성 시점부터 존재하는 paymentId로 실제 Refund 행을 먼저 잠근다.
    private java.util.Optional<Refund> findRefundForWebhook(String paymentId, String cancellationId) {
        var byPaymentId = paymentRepository.findByPaymentId(paymentId).flatMap(payment -> refundRepository.findWithLockByPayment_Id(payment.getId()));
        if (byPaymentId.isPresent()) {
            Refund refund = byPaymentId.get();
            String storedCancellationId = refund.getCancellationId();
            // 외부 응답을 받지 못해 cancellationId가 비어 있어도 paymentId로 후속 웹훅을 회수한다.
            // 이미 다른 cancellationId가 확정됐다면 중복 웹훅만 허용해 잘못된 완료 전이를 막는다.
            if (storedCancellationId == null || storedCancellationId.equals(cancellationId)) {
                return byPaymentId;
            }
            log.warn("event=REFUND_WEBHOOK_CANCELLATION_ID_MISMATCH paymentId={} refundId={} webhookCancellationId={} storedCancellationId={}",
                    paymentId, refund.getId(), cancellationId, storedCancellationId);
            return java.util.Optional.empty();
        }
        // paymentId로 찾지 못한 예외 경로에서는 이미 존재하는 cancellationId만 마지막으로 조회한다.
        return refundRepository.findWithLockByCancellationId(cancellationId);
    }

    private void logRefundCompletedAfterCommit(Refund refund) {
        Long completedRefundId = refund.getId();
        String completedPaymentId = refund.getPayment().getPaymentId();
        Long completedReservationId = refund.getPayment().getReservationId();
        Long completedParticipantId = refund.getPayment().getReservationParticipantId();
        BigDecimal completedAmount = refund.getAmount();
        RefundStatus completedRefundStatus = refund.getStatus();
        PaymentStatus completedPaymentStatus = refund.getPayment().getStatus();
        // 롤백된 환불이 완료 지표로 기록되지 않도록 커밋 이후에만 로그와 메트릭을 남긴다.
        AfterCommitExecutor.run(() -> {
            log.info(
                    "event=REFUND_COMPLETED refundId={} paymentId={} reservationId={} participantId={} amount={} afterStatus={} paymentAfterStatus={}",
                    completedRefundId, completedPaymentId, completedReservationId, completedParticipantId,
                    completedAmount, completedRefundStatus, completedPaymentStatus);
            businessMetricRecorder.increment(BusinessMetricEvent.REFUND_COMPLETED);
        });
    }

    public record RefundCompletion(RefundStatus refundStatus, Long reservationId,
                                   Long reservationParticipantId, Instant completedAt) {
        private static RefundCompletion from(Refund refund) {
            Payment payment = refund.getPayment();
            return new RefundCompletion(refund.getStatus(), payment.getReservationId(),
                    payment.getReservationParticipantId(), refund.getCompletedAt());
        }
    }

    public record RefundPreparation(Refund refund, boolean externalCallRequired) {
    }
}
