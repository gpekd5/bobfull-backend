package com.bobfull.restaurant.restaurant.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 소유자가 관리하는 식당 정보와 soft-delete 상태를 보관한다.
@Entity
@Table(name = "restaurant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Restaurant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "restaurant_id")
    private Long id;

    @Column(name = "owner_member_id", nullable = false)
    private Long ownerMemberId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, length = 100)
    private String keyword;

    @Column(name = "deposit_per_person", nullable = false)
    private Integer depositPerPerson;

    @Column(name = "image_key", length = 500)
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RestaurantStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Restaurant(
            Long ownerMemberId,
            String name,
            String address,
            String category,
            String description,
            String keyword,
            Integer depositPerPerson,
            String imageKey
    ) {
        this.ownerMemberId = ownerMemberId;
        this.name = name;
        this.address = address;
        this.category = category;
        this.description = description;
        this.keyword = keyword;
        this.depositPerPerson = depositPerPerson;
        this.imageKey = imageKey;
        this.status = RestaurantStatus.ACTIVE;
    }

    public static Restaurant create(
            Long ownerMemberId,
            String name,
            String address,
            String category,
            String description,
            String keyword,
            Integer depositPerPerson
    ) {
        return create(ownerMemberId, name, address, category, description, keyword, depositPerPerson, null);
    }

    public static Restaurant create(
            Long ownerMemberId,
            String name,
            String address,
            String category,
            String description,
            String keyword,
            Integer depositPerPerson,
            String imageKey
    ) {
        return new Restaurant(ownerMemberId, name, address, category, description, keyword, depositPerPerson, imageKey);
    }

    public void update(String name, String description, String keyword, Integer depositPerPerson) {
        this.name = name;
        this.description = description;
        this.keyword = keyword;
        this.depositPerPerson = depositPerPerson;
    }

    public void updateImageKey(String imageKey) {
        this.imageKey = imageKey;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isOwnedBy(Long memberId) {
        return this.ownerMemberId.equals(memberId);
    }

}
