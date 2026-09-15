package com.bobfull.restaurant.restaurant.infrastructure.repository;

import com.bobfull.admin.infrastructure.query.AdminRestaurantRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.query.RestaurantSearchRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository
        extends JpaRepository<Restaurant, Long>, RestaurantSearchRepository, AdminRestaurantRepository {

    Optional<Restaurant> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByImageKeyAndDeletedAtIsNull(String imageKey);

    boolean existsByImageKeyAndIdNotAndDeletedAtIsNull(String imageKey, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Restaurant r where r.id = :id and r.deletedAt is null")
    Optional<Restaurant> findByIdAndDeletedAtIsNullForUpdate(@Param("id") Long id);

    Page<Restaurant> findAllByOwnerMemberIdAndDeletedAtIsNull(Long ownerMemberId, Pageable pageable);
}
