package com.bobfull.restaurant.entity;

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

/**
 * OWNER가 소유·관리하는 식당이다(docs/data/erd.md 4.2).
 * 삭제는 소프트 딜리트이며, 연결된 테이블·회차·예약이 있을 때의 삭제 제한(Issue #31 결정 2,
 * RestaurantErrorCode.RESTAURANT_DELETE_NOT_ALLOWED)은 해당 도메인이 아직 없어 이번 Issue에서는
 * 검사하지 않는다. 합석 테이블·회차·예약 도메인 구현 시 softDelete 호출 전에 활성 데이터 여부를
 * 확인하는 검사를 추가해야 한다.
 */
@Entity
@Table(name = "restaurant")
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

    protected Restaurant() {
    }

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

    public Long getId() {
        return id;
    }

    public Long getOwnerMemberId() {
        return ownerMemberId;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getKeyword() {
        return keyword;
    }

    public Integer getDepositPerPerson() {
        return depositPerPerson;
    }

    public String getImageKey() {
        return imageKey;
    }

    public RestaurantStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
