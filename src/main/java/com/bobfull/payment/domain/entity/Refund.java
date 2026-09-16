package com.bobfull.payment.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 결제별 단일 전액 환불의 요청·처리·완료 상태와 외부 식별자를 관리한다.
@Entity
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, unique = true)
    private Payment payment;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancellation_id", unique = true, length = 64)
    private String cancellationId;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 256)
    private String idempotencyKey;

    @Column(name = "request_reason", nullable = false, updatable = false)
    private String requestReason;

    // 상태 변경 없이 재조회한 건도 순환할 수 있도록 updatedAt과 별도로 기록한다.
    @Column(name = "last_pg_checked_at")
    private Instant lastPgCheckedAt;

    private Refund(Payment payment, BigDecimal amount, RefundStatus status, Instant requestedAt, Instant completedAt,
                   String idempotencyKey, String requestReason) {
        this.payment = payment;
        this.amount = amount;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.idempotencyKey = idempotencyKey;
        this.requestReason = requestReason;
    }

    public static Refund create(Payment payment, BigDecimal amount, RefundStatus status, Instant requestedAt, Instant completedAt,
                                String idempotencyKey, String requestReason) {
        if (payment == null || amount == null || amount.signum() <= 0 || status == null) {
            throw new IllegalArgumentException("환불 결제, 금액, 상태는 필수입니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || requestReason == null || requestReason.isBlank()) {
            throw new IllegalArgumentException("환불 멱등성 키와 사유는 필수입니다.");
        }
        return new Refund(payment, amount, status, requestedAt, completedAt, idempotencyKey, requestReason);
    }

    public void markPgChecked(Instant checkedAt) {
        if (checkedAt == null) {
            throw new IllegalArgumentException("PG 조회 시각은 필수입니다.");
        }
        this.lastPgCheckedAt = checkedAt;
    }

    // COMPLETED·FAILED는 종료 상태이므로 뒤늦은 CancelPending 웹훅으로 되돌리지 않는다.
    public void markProcessing(String cancellationId) {
        if (status == RefundStatus.COMPLETED || status == RefundStatus.FAILED) {
            return;
        }
        this.cancellationId = cancellationId;
        this.status = RefundStatus.PROCESSING;
    }

    // FAILED는 자동으로 완료로 뒤집지 않고, COMPLETED는 중복 응답·웹훅에도 멱등하게 유지한다.
    public void complete(String cancellationId, Instant completedAt) {
        if (status == RefundStatus.COMPLETED || status == RefundStatus.FAILED) {
            return;
        }
        this.cancellationId = cancellationId;
        this.status = RefundStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    // 완료된 환불은 뒤늦은 실패 처리로 되돌리지 않는다.
    public void fail() {
        if (status != RefundStatus.COMPLETED) {
            this.status = RefundStatus.FAILED;
        }
    }
}
