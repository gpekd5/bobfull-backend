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

    // 즉시 응답과 환불 웹훅의 상태 전이를 직렬화해 완료 상태가 뒤늦은 갱신으로 덮이지 않게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockById(Long refundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockByCancellationId(String cancellationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockByPayment_Id(Long paymentId);

    // 영구히 매칭할 수 없는 환불을 계속 조회하지 않도록 재조정 대상의 나이에 상한을 둔다.
    // 상한을 넘긴 건은 반복된 오류 로그를 근거로 사람이 확인해야 하는 상태로 남긴다.
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
