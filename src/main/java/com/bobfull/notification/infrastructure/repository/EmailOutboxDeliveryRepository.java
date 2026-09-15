package com.bobfull.notification.infrastructure.repository;

import com.bobfull.notification.infrastructure.outbox.EmailDeliveryStatus;
import com.bobfull.notification.infrastructure.outbox.EmailOutboxDelivery;

import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailOutboxDeliveryRepository extends JpaRepository<EmailOutboxDelivery, Long> {
    List<EmailOutboxDelivery> findAllByOutboxEventIdAndStatus(Long outboxEventId, EmailDeliveryStatus status);
    boolean existsByOutboxEventIdAndStatus(Long outboxEventId, EmailDeliveryStatus status);
    @Modifying(clearAutomatically = true)
    @Query("update EmailOutboxDelivery d set d.status = :sent, d.sentAt = :now where d.id = :id and d.status = :pending")
    int markSent(@Param("id") Long id, @Param("pending") EmailDeliveryStatus pending,
                 @Param("sent") EmailDeliveryStatus sent, @Param("now") Instant now);
}
