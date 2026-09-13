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

/** Payment 전체 금액에 대한 단일 환불 처리 이력이다. */
@Entity
@Table(name = "refund")
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

    /** 마지막 PortOne 조회 시각이다. 상태 변경 시각(updatedAt)과 분리해 후보를 순환한다. */
    @Column(name = "last_pg_checked_at")
    private Instant lastPgCheckedAt;

    protected Refund() {
    }

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

    public Long getId() { return id; }
    public Payment getPayment() { return payment; }
    public BigDecimal getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public String getCancellationId() { return cancellationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestReason() { return requestReason; }
    public Instant getLastPgCheckedAt() { return lastPgCheckedAt; }

    public void markPgChecked(Instant checkedAt) {
        if (checkedAt == null) {
            throw new IllegalArgumentException("PG 조회 시각은 필수입니다.");
        }
        this.lastPgCheckedAt = checkedAt;
    }

    /**
     * 상태 전이는 단조롭게만 허용한다: REQUESTED/PROCESSING → PROCESSING. COMPLETED·FAILED는
     * 종료 상태라 이후 PROCESSING 전이를 무시한다(호출자가 호출 전후 상태를 비교해 역행 시도를
     * 로그로 남긴다).
     */
    public void markProcessing(String cancellationId) {
        if (status == RefundStatus.COMPLETED || status == RefundStatus.FAILED) return;
        this.cancellationId = cancellationId;
        this.status = RefundStatus.PROCESSING;
    }

    /**
     * REQUESTED/PROCESSING → COMPLETED만 허용한다. FAILED → COMPLETED는 PortOne이 명시적으로
     * 실패를 확정한 뒤 뒤늦은 Cancelled 웹훅이 도착하는 경우인데, 이를 자동으로 완료로 뒤집을지는
     * 정책 근거가 없어 이번에는 차단한다(호출자 로그로 남김). COMPLETED → COMPLETED는 중복 완료
     * 웹훅·즉시 응답 경쟁에 대비한 멱등 종료다.
     */
    public void complete(String cancellationId, Instant completedAt) {
        if (status == RefundStatus.COMPLETED || status == RefundStatus.FAILED) return;
        this.cancellationId = cancellationId;
        this.status = RefundStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    /** COMPLETED는 종료 상태라 FAILED로 되돌리지 않는다. */
    public void fail() {
        if (status != RefundStatus.COMPLETED) this.status = RefundStatus.FAILED;
    }
}
