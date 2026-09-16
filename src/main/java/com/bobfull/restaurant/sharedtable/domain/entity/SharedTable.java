package com.bobfull.restaurant.sharedtable.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 식당에서 회차와 정원을 구성하는 합석 테이블을 보관한다.
@Entity
@Table(name = "shared_table", indexes = @Index(name = "idx_shared_table_restaurant_id", columnList = "restaurant_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SharedTable extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shared_table_id")
    private Long id;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "display_number", nullable = false)
    private Integer displayNumber;

    @Column(nullable = false)
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SharedTableStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private SharedTable(Long restaurantId, Integer displayNumber, Integer capacity) {
        this.restaurantId = restaurantId;
        this.displayNumber = displayNumber;
        this.capacity = capacity;
        this.status = SharedTableStatus.ACTIVE;
    }

    public static SharedTable create(Long restaurantId, Integer capacity) {
        return new SharedTable(restaurantId, 1, capacity);
    }

    public static SharedTable create(Long restaurantId, Integer displayNumber, Integer capacity) {
        return new SharedTable(restaurantId, displayNumber, capacity);
    }

    public void updateCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

}
