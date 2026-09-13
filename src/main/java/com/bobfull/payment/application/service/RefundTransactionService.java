package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.payment.application.port.RefundIdempotencyKeyGenerator;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundTransactionService {
    private static final Logger log = LoggerFactory.getLogger(RefundTransactionService.class);
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final Clock clock;
    private final RefundIdempotencyKeyGenerator keyGenerator;
    private final BusinessMetricRecorder businessMetricRecorder;

    public RefundTransactionService(PaymentRepository paymentRepository, RefundRepository refundRepository, Clock clock,
                                    RefundIdempotencyKeyGenerator keyGenerator,
                                    BusinessMetricRecorder businessMetricRecorder) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.clock = clock;
        this.keyGenerator = keyGenerator;
        this.businessMetricRecorder = businessMetricRecorder;
    }

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
                        keyGenerator.generate(), cancelReason));
        log.info("event=REFUND_REQUESTED refundId={} paymentId={} reservationId={} participantId={} amount={} afterStatus={}",
                refund.getId(), payment.getPaymentId(), payment.getReservationId(),
                payment.getReservationParticipantId(), refund.getAmount(), refund.getStatus());
        return new RefundPreparation(refund, true);
    }

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPgChecked(Long refundId) {
        if (refundRepository.updateLastPgCheckedAt(refundId, clock.instant()) == 0) {
            throw new CustomException(PaymentErrorCode.REFUND_ID_NOT_FOUND);
        }
    }

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

    /**
     * paymentId로 먼저 찾고, 그 결과로 판단이 안 될 때만 cancellationId로 대신 찾는다. 원래는
     * cancellationId를 먼저 조회했는데, cancellation_id는 unique 컬럼이면서 최초 웹훅 시점에는
     * 어떤 행에도 그 값이 없다. 존재하지 않는 값을 unique 인덱스에서 찾는 조회(findWithLockByCancellationId)는
     * InnoDB가 그 값이 들어갈 빈 갭에 X 락을 걸게 만들고, 같은 그룹의 취소완료 웹훅 3건 이상이
     * cancellation_id가 전부 NULL인 상태로 동시에 도착하면 그 갭 락들이 서로 얽혀 실제 MySQL
     * 데드락이 났다(Issue #270, RefundCancellationIdGapLockMySqlConcurrencyIntegrationTest로 재현).
     * paymentId는 Refund 생성 시점부터 항상 이미 존재하는 값이라 이 조회는 항상 실제 행에 대한
     * 일반 락만 걸어 이 갭 락 자체가 생기지 않는다.
     *
     * <p>timeout·connection reset처럼 PortOne 응답을 파싱하기 전에 실패한 요청은 Refund에
     * cancellationId가 저장된 적이 없어(Refund.markProcessing/complete만 이 필드를 채운다)
     * cancellationId만으로는 이후 도착하는 웹훅과 영영 매칭되지 않는다. paymentId는 Refund 생성
     * 시점부터 Payment에 이미 있으므로 이 경로로 그 결과 불명확 요청도 웹훅이 회수할 수 있다.</p>
     *
     * <p>paymentId로 찾은 Refund에 이미 다른 cancellationId가 저장돼 있으면(예: PROCESSING으로
     * 확정된 다른 취소 시도) 이번 웹훅의 cancellationId와 일치하는 경우(중복 전달된 같은 웹훅)만
     * 통과시키고, 그 외에는 무시한다 — 그렇지 않으면 같은 Payment에 대한 서로 다른 취소 시도(웹훅)가
     * 기존 Refund를 엉뚱한 cancellationId로 덮어쓰고 완료 처리할 수 있다. paymentId로 못
     * 찾으면(Payment 자체가 없는 예외적인 경우) cancellationId로 마지막 시도를 한다 — 이미 존재하는
     * 값에 대한 조회라 갭 락 위험은 없다.</p>
     */
    private java.util.Optional<Refund> findRefundForWebhook(String paymentId, String cancellationId) {
        var byPaymentId = paymentRepository.findByPaymentId(paymentId).flatMap(payment -> refundRepository.findWithLockByPayment_Id(payment.getId()));
        if (byPaymentId.isPresent()) {
            Refund refund = byPaymentId.get();
            String storedCancellationId = refund.getCancellationId();
            if (storedCancellationId == null || storedCancellationId.equals(cancellationId)) {
                return byPaymentId;
            }
            log.warn("event=REFUND_WEBHOOK_CANCELLATION_ID_MISMATCH paymentId={} refundId={} webhookCancellationId={} storedCancellationId={}",
                    paymentId, refund.getId(), cancellationId, storedCancellationId);
            return java.util.Optional.empty();
        }
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
