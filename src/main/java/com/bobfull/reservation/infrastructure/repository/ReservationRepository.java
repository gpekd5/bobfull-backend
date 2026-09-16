package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.admin.infrastructure.repository.query.AdminReservationRepository;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.query.OwnerReservationQueryRepository;
import com.bobfull.reservation.infrastructure.repository.query.ReservationSearchRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long>, ReservationSearchRepository, AdminReservationRepository,
        OwnerReservationQueryRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findWithLockById(Long reservationId);

    boolean existsByTimeSlotIdAndReservationStatusIn(Long timeSlotId, Collection<ReservationStatus> statuses);

    Optional<Reservation> findByTimeSlotIdAndReservationStatusIn(Long timeSlotId, Collection<ReservationStatus> statuses);

    /**
     * 여러 TimeSlot에 걸친 활성/CLOSED 예약을 한 번에 조회한다(Issue #235, 인기 회차 조회
     * Hot-path에서 회차별로 반복 조회하던 것을 배치로 묶기 위함). 같은 TimeSlot에 같은 statuses
     * 조건을 만족하는 Reservation은 최대 1건이라는 전제(CREATE 배타 선점, ADR-0001)를 그대로 쓴다.
     */
    List<Reservation> findAllByTimeSlotIdInAndReservationStatusIn(Collection<Long> timeSlotIds, Collection<ReservationStatus> statuses);

    boolean existsByTimeSlotIdInAndReservationStatusIn(Collection<Long> timeSlotIds, Collection<ReservationStatus> statuses);

    Page<Reservation> findAllByTimeSlotIdIn(Collection<Long> timeSlotIds, Pageable pageable);

    long countByReservationStatus(ReservationStatus status);

    @Query("select r from Reservation r join TimeSlot ts on r.timeSlotId = ts.id "
            + "join SharedTable st on ts.sharedTableId = st.id "
            + "where st.restaurantId = :restaurantId "
            + "and (:startAt is null or ts.startAt >= :startAt) and (:endAt is null or ts.startAt < :endAt)")
    Page<Reservation> findSettlementReservations(
            @Param("restaurantId") Long restaurantId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt,
            Pageable pageable
    );

    /**
     * 모집 마감 기한(식사 시작 2시간 전) 도달 후보를 조회한다(Issue #47). TimeSlot과 조인해
     * 회차 시작 시각을 직접 비교하며, 아직 모집 마감·취소 처리되지 않은 대상만 후보로 삼는다.
     */
    @Query("select r.id from Reservation r join TimeSlot ts on r.timeSlotId = ts.id "
            + "where r.recruitmentStatus = :recruitmentStatus and r.reservationStatus in :activeStatuses "
            + "and ts.startAt <= :deadline "
            + "order by ts.startAt asc")
    List<Long> findRecruitmentDeadlineCandidateIds(
            @Param("recruitmentStatus") RecruitmentStatus recruitmentStatus,
            @Param("activeStatuses") Collection<ReservationStatus> activeStatuses,
            @Param("deadline") Instant deadline,
            Pageable pageable
    );

    /**
     * 식사 종료(TimeSlot.endAt 도달) 후보를 조회한다(Issue #175 Q1·Q2). {@code CONFIRMED} 예약만
     * 대상으로 삼아, 식사 종료 시점까지 {@code RECRUITING}으로 남은 예약은 자동 종료로 덮지 않는다.
     */
    @Query("select r.id from Reservation r join TimeSlot ts on r.timeSlotId = ts.id "
            + "where r.reservationStatus = :reservationStatus and ts.endAt <= :now "
            + "order by ts.endAt asc")
    List<Long> findDiningEndCandidateIds(
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("now") Instant now,
            Pageable pageable
    );
}
