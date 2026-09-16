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

    // 회차별 반복 조회를 피하되, CREATE 배타 선점으로 회차별 대상 예약이 최대 1건이라는 전제를 쓴다.
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

    // 식사 시작 2시간 전까지 모집 중이고 아직 취소되지 않은 예약만 마감 후보로 조회한다.
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

    // CONFIRMED만 종료 후보로 삼아 성사되지 않은 RECRUITING 예약을 CLOSED로 덮지 않는다.
    @Query("select r.id from Reservation r join TimeSlot ts on r.timeSlotId = ts.id "
            + "where r.reservationStatus = :reservationStatus and ts.endAt <= :now "
            + "order by ts.endAt asc")
    List<Long> findDiningEndCandidateIds(
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("now") Instant now,
            Pageable pageable
    );
}
