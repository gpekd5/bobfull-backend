package com.bobfull.admin.repository;

import com.bobfull.admin.dto.AdminRestaurantResult;
import com.bobfull.member.domain.entity.QMember;
import com.bobfull.restaurant.restaurant.domain.entity.QRestaurant;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

public class AdminRestaurantRepositoryImpl implements AdminRestaurantRepository {

    private final JPAQueryFactory queryFactory;

    public AdminRestaurantRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<AdminRestaurantResult> searchRestaurants(
            String keyword, RestaurantStatus status, Boolean deleted, Pageable pageable
    ) {
        QRestaurant restaurant = QRestaurant.restaurant;
        QMember owner = QMember.member;

        BooleanBuilder predicates = predicates(restaurant, keyword, status, deleted);

        var content = queryFactory
                .select(projection(restaurant, owner))
                .from(restaurant)
                .join(owner).on(owner.id.eq(restaurant.ownerMemberId))
                .where(predicates)
                .orderBy(restaurant.createdAt.desc(), restaurant.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(restaurant.id.count())
                .from(restaurant)
                .where(predicates)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public Optional<AdminRestaurantResult> findRestaurantDetail(Long restaurantId) {
        QRestaurant restaurant = QRestaurant.restaurant;
        QMember owner = QMember.member;

        AdminRestaurantResult result = queryFactory
                .select(projection(restaurant, owner))
                .from(restaurant)
                .join(owner).on(owner.id.eq(restaurant.ownerMemberId))
                .where(restaurant.id.eq(restaurantId))
                .fetchFirst();
        return Optional.ofNullable(result);
    }

    private com.querydsl.core.types.Expression<AdminRestaurantResult> projection(QRestaurant restaurant, QMember owner) {
        return Projections.constructor(
                AdminRestaurantResult.class,
                restaurant.id,
                restaurant.ownerMemberId,
                owner.name,
                restaurant.name,
                restaurant.address,
                restaurant.category,
                restaurant.description,
                restaurant.keyword,
                restaurant.depositPerPerson,
                restaurant.status,
                restaurant.createdAt,
                restaurant.deletedAt
        );
    }

    private BooleanBuilder predicates(QRestaurant restaurant, String keyword, RestaurantStatus status, Boolean deleted) {
        BooleanBuilder predicates = new BooleanBuilder();
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            predicates.and(restaurant.name.containsIgnoreCase(trimmed).or(restaurant.keyword.containsIgnoreCase(trimmed)));
        }
        if (status != null) {
            predicates.and(restaurant.status.eq(status));
        }
        if (deleted != null) {
            predicates.and(deleted ? restaurant.deletedAt.isNotNull() : restaurant.deletedAt.isNull());
        }
        return predicates;
    }
}
