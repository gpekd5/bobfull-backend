package com.bobfull.restaurant.sharedtable.infrastructure.repository;

import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SharedTableRepository extends JpaRepository<SharedTable, Long> {

    Optional<SharedTable> findByIdAndDeletedAtIsNull(Long id);

    Page<SharedTable> findAllByRestaurantIdAndDeletedAtIsNull(Long restaurantId, Pageable pageable);

    List<SharedTable> findAllByRestaurantIdAndDeletedAtIsNull(Long restaurantId);

    List<SharedTable> findAllByIdInAndDeletedAtIsNull(Collection<Long> ids);

    @Query("select coalesce(max(t.displayNumber), 0) from SharedTable t where t.restaurantId = :restaurantId")
    Integer findMaxDisplayNumberByRestaurantId(@Param("restaurantId") Long restaurantId);
}
