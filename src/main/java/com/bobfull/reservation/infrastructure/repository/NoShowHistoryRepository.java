package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.reservation.domain.entity.NoShowHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoShowHistoryRepository extends JpaRepository<NoShowHistory, Long> {
}
