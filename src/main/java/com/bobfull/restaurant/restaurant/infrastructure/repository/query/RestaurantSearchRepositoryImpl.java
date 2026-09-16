package com.bobfull.restaurant.restaurant.infrastructure.repository.query;

import com.bobfull.restaurant.restaurant.presentation.request.RestaurantSearchRequest;
import com.bobfull.restaurant.restaurant.domain.entity.QRestaurant;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import com.bobfull.restaurant.sharedtable.domain.entity.QSharedTable;
import com.bobfull.restaurant.timeslot.domain.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

public class RestaurantSearchRepositoryImpl implements RestaurantSearchRepository {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final JPAQueryFactory queryFactory;

    public RestaurantSearchRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    /**
     * content/count 두 쿼리를 하나의 읽기 전용 트랜잭션으로 묶는다(PR #202 리뷰 반영). 이 커스텀
     * fragment 구현은 {@code SimpleJpaRepository}를 상속하지 않아 Spring Data의 기본 트랜잭션
     * advice가 자동으로 적용되지 않으므로, 명시적으로 선언해야 두 쿼리가 같은 시점의 데이터를
     * 본다는 보장이 생긴다(그렇지 않으면 두 쿼리 사이에 동시 쓰기가 끼어들어 content와
     * totalElements가 서로 다른 시점을 반영할 수 있다).
     */
    @Override
    @Transactional(readOnly = true)
    public Page<Restaurant> search(RestaurantSearchRequest request, Pageable pageable) {
        QRestaurant restaurant = QRestaurant.restaurant;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;

        boolean requiresTimeSlot = request.date() != null || request.time() != null;
        BooleanBuilder predicates = basePredicates(restaurant, request);
        if (requiresTimeSlot) {
            predicates.and(sharedTable.restaurantId.eq(restaurant.id));
            predicates.and(sharedTable.deletedAt.isNull());
            predicates.and(timeSlot.sharedTableId.eq(sharedTable.id));
            predicates.and(timeSlot.deletedAt.isNull());
            predicates.and(timeSlotCondition(timeSlot, request.date(), request.time()));
        }

        JPAQuery<Restaurant> contentQuery = queryFactory.selectDistinct(restaurant);
        JPAQuery<Long> countQuery = queryFactory.select(restaurant.id.countDistinct());
        if (requiresTimeSlot) {
            contentQuery.from(restaurant, sharedTable, timeSlot);
            countQuery.from(restaurant, sharedTable, timeSlot);
        } else {
            contentQuery.from(restaurant);
            countQuery.from(restaurant);
        }

        List<Restaurant> content = contentQuery
                .where(predicates)
                .orderBy(restaurantOrderSpecifiers(pageable.getSort(), restaurant))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = countQuery.where(predicates).fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private BooleanBuilder basePredicates(QRestaurant restaurant, RestaurantSearchRequest request) {
        BooleanBuilder predicates = new BooleanBuilder();
        predicates.and(restaurant.deletedAt.isNull());
        predicates.and(restaurant.status.eq(RestaurantStatus.ACTIVE));

        if (StringUtils.hasText(request.keyword())) {
            String keyword = request.keyword().trim();
            predicates.and(restaurant.name.containsIgnoreCase(keyword)
                    .or(restaurant.keyword.containsIgnoreCase(keyword)));
        }
        if (StringUtils.hasText(request.category())) {
            predicates.and(restaurant.category.eq(request.category().trim()));
        }
        return predicates;
    }

    private BooleanExpression timeSlotCondition(QTimeSlot timeSlot, LocalDate date, LocalTime time) {
        if (date != null && time != null) {
            return timeSlot.startAt.eq(toInstant(date, time));
        }
        if (date != null) {
            DateRange dateRange = toDateRange(date);
            return timeSlot.startAt.goe(dateRange.startAt())
                    .and(timeSlot.startAt.lt(dateRange.endAt()));
        }
        return localTimeCondition(timeSlot, time);
    }

    private BooleanExpression localTimeCondition(QTimeSlot timeSlot, LocalTime time) {
        LocalTime utcTime = time.minusHours(9);
        BooleanExpression expression = Expressions.numberTemplate(Integer.class, "hour({0})", timeSlot.startAt)
                .eq(utcTime.getHour())
                .and(Expressions.numberTemplate(Integer.class, "minute({0})", timeSlot.startAt)
                        .eq(utcTime.getMinute()));
        if (utcTime.getSecond() != 0) {
            expression = expression.and(Expressions.numberTemplate(Integer.class, "second({0})", timeSlot.startAt)
                    .eq(utcTime.getSecond()));
        }
        return expression;
    }

    private OrderSpecifier<?>[] restaurantOrderSpecifiers(Sort sort, QRestaurant restaurant) {
        List<OrderSpecifier<?>> orderSpecifiers = new ArrayList<>();
        Order recentDirection = null;
        for (Sort.Order order : sort) {
            OrderSpecifier<?> orderSpecifier = restaurantOrderSpecifier(order, restaurant);
            if (orderSpecifier != null) {
                orderSpecifiers.add(orderSpecifier);
            }
            if (order.getProperty().equals("createdAt") || order.getProperty().equals("recent")) {
                recentDirection = order.isAscending() ? Order.ASC : Order.DESC;
            }
        }
        if (orderSpecifiers.isEmpty()) {
            orderSpecifiers.add(restaurant.id.asc());
        } else if (recentDirection != null) {
            orderSpecifiers.add(order(recentDirection, restaurant.id));
        }
        return orderSpecifiers.toArray(OrderSpecifier[]::new);
    }

    private OrderSpecifier<?> restaurantOrderSpecifier(Sort.Order order, QRestaurant restaurant) {
        Order direction = order.isAscending() ? Order.ASC : Order.DESC;
        return switch (order.getProperty()) {
            case "restaurantId" -> order(direction, restaurant.id);
            case "name" -> order(direction, restaurant.name);
            case "category" -> order(direction, restaurant.category);
            case "depositPerPerson" -> order(direction, restaurant.depositPerPerson);
            case "createdAt", "recent" -> order(direction, restaurant.createdAt);
            default -> null;
        };
    }

    private <T extends Comparable<?>> OrderSpecifier<T> order(
            Order direction,
            ComparableExpressionBase<T> expression
    ) {
        return new OrderSpecifier<>(direction, expression);
    }

    private DateRange toDateRange(LocalDate date) {
        return new DateRange(toInstant(date, LocalTime.MIN), toInstant(date.plusDays(1), LocalTime.MIN));
    }

    private Instant toInstant(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(SEOUL_ZONE).toInstant();
    }

    private record DateRange(Instant startAt, Instant endAt) {
    }
}
