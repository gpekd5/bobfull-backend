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

// 식당 조건·회차 시간·정렬을 조합한 사용자 검색을 QueryDSL로 구현한다.
public class RestaurantSearchRepositoryImpl implements RestaurantSearchRepository {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final JPAQueryFactory queryFactory;

    public RestaurantSearchRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    // custom fragment에는 기본 트랜잭션이 적용되지 않아 content/count를 같은 읽기 시점으로 명시한다.
    @Override
    @Transactional(readOnly = true)
    public Page<Restaurant> search(RestaurantSearchRequest request, Pageable pageable) {
        QRestaurant restaurant = QRestaurant.restaurant;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;

        boolean requiresTimeSlot = request.date() != null || request.time() != null;
        BooleanBuilder predicates = basePredicates(restaurant, request);
        // 시간 조건이 없으면 불필요한 관계 조회를 피하고, 있을 때만 활성 테이블·회차를 검색에 포함한다.
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

    // startAt은 UTC로 저장되므로 시각 단독 검색은 서울 시각을 UTC 시각으로 바꿔 비교한다.
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
            // 같은 생성 시각에서도 페이지 순서가 흔들리지 않도록 id를 같은 방향의 tie-breaker로 쓴다.
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
