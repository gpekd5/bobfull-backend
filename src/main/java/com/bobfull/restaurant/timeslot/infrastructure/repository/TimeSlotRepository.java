package com.bobfull.restaurant.timeslot.infrastructure.repository;

import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long> {

    Optional<TimeSlot> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TimeSlot> findWithLockByIdAndDeletedAtIsNull(Long id);

    boolean existsBySharedTableIdAndDeletedAtIsNull(Long sharedTableId);

    List<TimeSlot> findAllBySharedTableIdAndDeletedAtIsNull(Long sharedTableId);

    boolean existsBySharedTableIdAndStartAtAndDeletedAtIsNull(Long sharedTableId, Instant startAt);

    boolean existsBySharedTableIdAndStartAtAndDeletedAtIsNullAndIdNot(
            Long sharedTableId,
            Instant startAt,
            Long id
    );

    Page<TimeSlot> findAllBySharedTableIdInAndDeletedAtIsNullOrderByStartAtAsc(
            Collection<Long> sharedTableIds,
            Pageable pageable
    );

    Page<TimeSlot> findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
            Collection<Long> sharedTableIds,
            Instant startAtInclusive,
            Instant startAtExclusive,
            Pageable pageable
    );

    List<TimeSlot> findAllBySharedTableIdInAndStartAtGreaterThanEqualAndStartAtLessThanAndDeletedAtIsNullOrderByStartAtAsc(
            Collection<Long> sharedTableIds,
            Instant startAtInclusive,
            Instant startAtExclusive
    );
}
