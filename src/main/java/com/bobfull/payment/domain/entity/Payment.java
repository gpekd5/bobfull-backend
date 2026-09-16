package com.bobfull.payment.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 결제 준비부터 완료·만료·환불까지 내부 결제 상태와 예약 연결 정보를 관리한다.
@Entity
@Table(name = "payment", indexes = {
        @jakarta.persistence.Index(name = "idx_payment_status_expires_at_id", columnList = "payment_status, expires_at, payment_id"),
        @jakarta.persistence.Index(name = "idx_payment_reservation_id", columnList = "reservation_id"),
        @jakarta.persistence.Index(name = "idx_payment_time_slot_id", columnList = "time_slot_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    public static final String CURRENCY_KRW = "KRW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @Column(name = "portone_payment_id", nullable = false, unique = true, length = 64)
    private String paymentId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "time_slot_id", nullable = false)
    private Long timeSlotId;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "reservation_participant_id", unique = true)
    private Long reservationParticipantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_purpose", nullable = false, length = 20)
    private PaymentPurpose purpose;

    @Column(name = "party_size", nullable = false)
    private Integer partySize;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    private Payment(
            String paymentId,
            Long memberId,
            Long timeSlotId,
            Long reservationId,
            PaymentPurpose purpose,
            Integer partySize,
            BigDecimal amount,
            Instant expiresAt
    ) {
        this.paymentId = paymentId;
        this.memberId = memberId;
        this.timeSlotId = timeSlotId;
        this.reservationId = reservationId;
        this.purpose = purpose;
        this.partySize = partySize;
        this.amount = amount;
        this.currency = CURRENCY_KRW;
        this.status = PaymentStatus.READY;
        this.expiresAt = expiresAt;
    }

    public static Payment createReady(
            String paymentId,
            Long memberId,
            Long timeSlotId,
            Long reservationId,
            PaymentPurpose purpose,
            Integer partySize,
            BigDecimal amount,
            Instant expiresAt
    ) {
        validateReadyCreation(paymentId, memberId, timeSlotId, reservationId, purpose, partySize, amount, expiresAt);
        return new Payment(paymentId, memberId, timeSlotId, reservationId, purpose, partySize, amount, expiresAt);
    }

    private static void validateReadyCreation(
            String paymentId,
            Long memberId,
            Long timeSlotId,
            Long reservationId,
            PaymentPurpose purpose,
            Integer partySize,
            BigDecimal amount,
            Instant expiresAt
    ) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId는 필수입니다.");
        }
        if (memberId == null || memberId <= 0 || timeSlotId == null || timeSlotId <= 0) {
            throw new IllegalArgumentException("결제 회원과 대상 회차는 필수입니다.");
        }
        if (purpose == null || partySize == null || partySize <= 0) {
            throw new IllegalArgumentException("결제 목적과 partySize는 필수이며 partySize는 양수여야 합니다.");
        }
        if (purpose == PaymentPurpose.CREATE && reservationId != null) {
            throw new IllegalArgumentException("CREATE 결제는 reservationId를 가질 수 없습니다.");
        }
        if (purpose == PaymentPurpose.JOIN && (reservationId == null || reservationId <= 0)) {
            throw new IllegalArgumentException("JOIN 결제는 reservationId가 필요합니다.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount는 양수여야 합니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt은 필수입니다.");
        }
    }

    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    public void complete(Instant paidAt) {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("READY Payment만 완료할 수 있습니다.");
        }
        status = PaymentStatus.PAID;
        this.paidAt = paidAt;
    }

    public boolean expireIfNeeded(Instant now) {
        if (status != PaymentStatus.READY || expiresAt.isAfter(now)) {
            return false;
        }
        status = PaymentStatus.EXPIRED;
        return true;
    }

    // 전체 환불 뒤에도 결제 완료 이력을 보존하도록 paidAt은 유지한다.
    public void markRefunded() {
        if (status != PaymentStatus.PAID) {
            throw new IllegalStateException("PAID Payment만 환불 완료로 전이할 수 있습니다.");
        }
        status = PaymentStatus.REFUNDED;
    }

    public void attachReservationConfirmation(Long reservationId, Long reservationParticipantId) {
        if (reservationId == null || reservationParticipantId == null) {
            throw new IllegalArgumentException("예약과 참여자 식별자는 필수입니다.");
        }
        this.reservationId = reservationId;
        this.reservationParticipantId = reservationParticipantId;
    }
}
