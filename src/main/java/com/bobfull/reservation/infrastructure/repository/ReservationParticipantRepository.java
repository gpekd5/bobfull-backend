package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.query.MyReservationQueryRepository;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationParticipantRepository extends JpaRepository<ReservationParticipant, Long>, MyReservationQueryRepository {

    boolean existsByReservationIdAndMemberId(Long reservationId, Long memberId);

    boolean existsByReservationId(Long reservationId);

    /**
     * 취소 완료 확정 직전 "남은 CANCEL_REQUESTED가 있는지"를 잠금 조회로 판단한다(Issue #259).
     * MySQL 기본 격리수준(REPEATABLE READ)에서는 트랜잭션의 첫 번째 잠금 없는 SELECT가 그 트랜잭션
     * 전체의 스냅샷 시점을 고정한다. 이 흐름이 실행되는 트랜잭션(웹훅 완료 처리)은 Reservation 락보다
     * 먼저 Refund→Payment의 LAZY 연관관계 로딩 같은 잠금 없는 SELECT가 먼저 실행될 수 있어, 잠금 없는
     * exists 조회로는 방금 커밋된 다른 참여자의 상태를 못 볼 수 있다. 잠금 조회는 트랜잭션에 이미
     * 고정된 스냅샷과 무관하게 항상 최신 커밋 데이터를 읽으므로 이 문제를 우회한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from ReservationParticipant p "
            + "where p.reservationId = :reservationId and p.participationStatus = :status")
    List<ReservationParticipant> findAllWithLockByReservationIdAndParticipationStatus(
            @Param("reservationId") Long reservationId, @Param("status") ParticipationStatus status);

    Optional<ReservationParticipant> findByReservationIdAndMemberId(Long reservationId, Long memberId);

    List<ReservationParticipant> findAllByReservationIdAndParticipationStatus(
            Long reservationId, ParticipationStatus participationStatus);

    @Query("select coalesce(sum(p.partySize), 0) from ReservationParticipant p "
            + "where p.reservationId = :reservationId and p.participationStatus = :status")
    int sumPartySize(@Param("reservationId") Long reservationId, @Param("status") ParticipationStatus status);

    /**
     * 여러 참여 상태에 걸친 partySize 합계다. 취소 접수(CANCEL_REQUESTED) 참여자는 환불이 완료되기
     * 전까지 좌석을 계속 점유한 상태로 집계해야 하므로(Issue #44), RESERVED와 함께 넘겨 합산한다.
     */
    @Query("select coalesce(sum(p.partySize), 0) from ReservationParticipant p "
            + "where p.reservationId = :reservationId and p.participationStatus in :statuses")
    int sumPartySizeByStatuses(
            @Param("reservationId") Long reservationId, @Param("statuses") Collection<ParticipationStatus> statuses);

    /**
     * {@link #sumPartySizeByStatuses}와 같은 조건의 참여자 목록을 잠금 조회한다(Issue #264). SUM
     * 집계 쿼리는 JPA 스펙상 엔티티가 아닌 결과를 반환해 {@code @Lock}의 이식성이 보장되지
     * 않으므로, 이미 트랜잭션 안에서 스냅샷이 고정된 뒤에도 최신 커밋을 보게 하려면 엔티티 목록을
     * 잠금 조회해 호출자가 Java에서 합산해야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from ReservationParticipant p "
            + "where p.reservationId = :reservationId and p.participationStatus in :statuses")
    List<ReservationParticipant> findAllWithLockByReservationIdAndParticipationStatusIn(
            @Param("reservationId") Long reservationId, @Param("statuses") Collection<ParticipationStatus> statuses);

    /**
     * 여러 Reservation에 걸친 partySize 합계를 Reservation별로 묶어 한 번에 반환한다(Issue #235,
     * 인기 회차 조회 Hot-path에서 예약별로 반복 조회하던 것을 배치로 묶기 위함). 각 행은
     * {@code [reservationId, sumPartySize]}이며, 참여자가 없는 Reservation은 결과에 나타나지
     * 않는다(호출자가 0으로 취급해야 한다).
     */
    @Query("select p.reservationId, coalesce(sum(p.partySize), 0) from ReservationParticipant p "
            + "where p.reservationId in :reservationIds and p.participationStatus in :statuses "
            + "group by p.reservationId")
    List<Object[]> sumPartySizeByReservationIdsAndStatuses(
            @Param("reservationIds") Collection<Long> reservationIds,
            @Param("statuses") Collection<ParticipationStatus> statuses);

    Page<ReservationParticipant> findAllByReservationIdAndParticipationStatus(
            Long reservationId, ParticipationStatus status, Pageable pageable);

    /** §6-13 사장님용 참여자 목록 조회용이다. 상태 제한 없이 신청 이력 전체를 조회한다(Issue #147). */
    Page<ReservationParticipant> findAllByReservationId(Long reservationId, Pageable pageable);

    /**
     * CANCEL_REQUESTED인 참여자만 CANCELLED로 조건부 전환하는 원자적 UPDATE다(Issue #44 최종 계약).
     * 즉시 응답·웹훅·재확인 스케줄러가 같은 참여자를 동시에 완료 처리하려 해도, 이 조건절 덕분에
     * 오직 하나의 호출만 실제로 행을 갱신해 처리권을 얻는다 — 반환값이 1이면 이 호출이 처리권을
     * 얻은 것이고, 0이면 이미 다른 경로가 완료했거나 CANCEL_REQUESTED 상태가 아니므로 멱등 종료한다.
     */
    @Modifying
    @Query("update ReservationParticipant p set p.participationStatus = com.bobfull.reservation.domain.entity.ParticipationStatus.CANCELLED, "
            + "p.cancelledAt = :cancelledAt "
            + "where p.id = :id and p.participationStatus = com.bobfull.reservation.domain.entity.ParticipationStatus.CANCEL_REQUESTED")
    int completeCancelIfRequested(@Param("id") Long id, @Param("cancelledAt") Instant cancelledAt);

    Optional<ReservationParticipant> findByIdAndReservationId(Long id, Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationParticipant> findWithLockByIdAndReservationId(Long id, Long reservationId);

    long countByParticipationStatus(ParticipationStatus status);

    long countByParticipationStatusIn(Collection<ParticipationStatus> statuses);
}
