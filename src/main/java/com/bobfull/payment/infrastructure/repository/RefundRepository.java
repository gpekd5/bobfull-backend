package com.bobfull.payment.infrastructure.repository;

import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPayment_Id(Long paymentId);

    Optional<Refund> findByCancellationId(String cancellationId);

    /**
     * Refund 상태 전이(REQUESTED/PROCESSING/COMPLETED/FAILED) 경로를 직렬화하기 위한 비관적 락
     * 조회다. 즉시 응답·CancelPending·Cancelled 웹훅이 같은 Refund를 동시에 갱신하려 할 때, 락 없는
     * 조회는 각 트랜잭션이 읽은 메모리 스냅샷만으로 판단해 나중에 커밋된 값이 앞선 완료 상태를
     * 덮어쓰는 lost-update를 막지 못한다. 이 락 조회는 뒤 트랜잭션이 앞 트랜잭션의 커밋을 기다린 뒤
     * 최신 상태를 다시 읽게 강제한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockById(Long refundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockByCancellationId(String cancellationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockByPayment_Id(Long paymentId);

    /**
     * updatedAfter는 영구히 매칭 불가능한 환불 건에 재시도를 계속 낭비하지 않도록 재조정 대상의
     * 나이에 상한을 둔다(Issue #272). max-age(기본 24시간)보다 오래된 건은 더 이상 후보에 넣지
     * 않는다 — 그 시점까지 이미 여러 차례 ERROR 로그로 escalate됐으므로(스케줄러의
     * REFUND_RECONCILIATION_REQUIRED 로그) 사람이 수동으로 확인해야 하는 상태로 남긴다.
     */
    @EntityGraph(attributePaths = "payment")
    @org.springframework.data.jpa.repository.Query("select r from Refund r "
            + "where r.status in :statuses and r.updatedAt >= :updatedAfter and r.updatedAt <= :updatedBefore "
            + "and (r.lastPgCheckedAt is null or r.lastPgCheckedAt <= :checkedBefore) "
            + "order by coalesce(r.lastPgCheckedAt, r.updatedAt) asc, r.id asc")
    List<Refund> findReconciliationCandidates(
            @org.springframework.data.repository.query.Param("statuses") List<RefundStatus> statuses,
            @org.springframework.data.repository.query.Param("updatedAfter") Instant updatedAfter,
            @org.springframework.data.repository.query.Param("updatedBefore") Instant updatedBefore,
            @org.springframework.data.repository.query.Param("checkedBefore") Instant checkedBefore,
            org.springframework.data.domain.Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Refund r set r.lastPgCheckedAt = :checkedAt where r.id = :refundId")
    int updateLastPgCheckedAt(@org.springframework.data.repository.query.Param("refundId") Long refundId,
                              @org.springframework.data.repository.query.Param("checkedAt") Instant checkedAt);

    @EntityGraph(attributePaths = "payment")
    Page<Refund> findAllByPayment_MemberId(Long memberId, Pageable pageable);

    @EntityGraph(attributePaths = "payment")
    Page<Refund> findAllByStatus(RefundStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "payment")
    Page<Refund> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "payment")
    Page<Refund> findAllByPayment_MemberIdAndStatus(Long memberId, RefundStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "payment")
    Optional<Refund> findByIdAndPayment_MemberId(Long refundId, Long memberId);

    @EntityGraph(attributePaths = "payment")
    java.util.List<Refund> findAllByPayment_ReservationId(Long reservationId);

    @EntityGraph(attributePaths = "payment")
    java.util.List<Refund> findAllByPayment_ReservationIdIn(java.util.Collection<Long> reservationIds);
}
