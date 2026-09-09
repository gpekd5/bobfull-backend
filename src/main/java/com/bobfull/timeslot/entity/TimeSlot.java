package com.bobfull.timeslot.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.GeneratedColumn;

/**
 * 합석 테이블에서 예약 가능한 단일 회차다(docs/data/erd.md 4.4).
 */
@Entity
@Table(
        name = "time_slot",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_time_slot_active_start",
                        columnNames = {"shared_table_id", "active_start_at"}
                )
        }
)
public class TimeSlot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "time_slot_id")
    private Long id;

    @Column(name = "shared_table_id", nullable = false)
    private Long sharedTableId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @GeneratedColumn("case when deleted_at is null then start_at else null end")
    @Column(name = "active_start_at", insertable = false, updatable = false)
    private Instant activeStartAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected TimeSlot() {
    }

    private TimeSlot(Long sharedTableId, Instant startAt, Instant endAt) {
        this.sharedTableId = sharedTableId;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public static TimeSlot create(Long sharedTableId, Instant startAt, Instant endAt) {
        return new TimeSlot(sharedTableId, startAt, endAt);
    }

    public void update(Instant startAt, Instant endAt) {
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSharedTableId() {
        return sharedTableId;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
