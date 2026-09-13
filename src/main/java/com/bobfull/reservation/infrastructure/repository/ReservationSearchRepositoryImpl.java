package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.payment.entity.PaymentStatus;
import com.bobfull.payment.entity.QPayment;
import com.bobfull.reservation.presentation.dto.ReservationSearchRequest;
import com.bobfull.reservation.application.dto.ReservationSearchResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.restaurant.restaurant.domain.entity.QRestaurant;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import com.bobfull.restaurant.sharedtable.domain.entity.QSharedTable;
import com.bobfull.restaurant.timeslot.domain.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
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
import org.springframework.util.StringUtils;

public class ReservationSearchRepositoryImpl implements ReservationSearchRepository {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED);

    private final JPAQueryFactory queryFactory;

    public ReservationSearchRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<ReservationSearchResult> searchRecruitingReservations(
            ReservationSearchRequest request,
            Instant now,
            Pageable pageable
    ) {
        QReservation reservation = QReservation.reservation;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QRestaurant restaurant = QRestaurant.restaurant;

        JPQLQuery<Long> currentParticipantCount = currentParticipantCount(reservation);
        JPQLQuery<Long> temporaryHeldCount = temporaryHeldCount(timeSlot, now);
        NumberExpression<Long> availableCapacity = sharedTable.capacity.longValue()
                .subtract(currentParticipantCount)
                .subtract(temporaryHeldCount);

        BooleanBuilder predicates = predicates(
                request, reservation, timeSlot, sharedTable, restaurant, availableCapacity);

        List<ReservationSearchResult> content = queryFactory
                .select(Projections.constructor(
                        ReservationSearchResult.class,
                        reservation.id,
                        restaurant.id,
                        restaurant.name,
                        timeSlot.id,
                        sharedTable.id,
                        sharedTable.capacity,
                        timeSlot.startAt,
                        timeSlot.endAt,
                        reservation.reservationStatus,
                        reservation.recruitmentStatus,
                        currentParticipantCount,
                        temporaryHeldCount
                ))
                .from(reservation, timeSlot, sharedTable, restaurant)
                .where(predicates)
                .orderBy(reservationOrderSpecifiers(
                        pageable.getSort(), reservation, timeSlot, sharedTable, restaurant,
                        currentParticipantCount, availableCapacity))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(reservation.id.countDistinct())
                .from(reservation, timeSlot, sharedTable, restaurant)
                .where(predicates)
                .fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private BooleanBuilder predicates(
            ReservationSearchRequest request,
            QReservation reservation,
            QTimeSlot timeSlot,
            QSharedTable sharedTable,
            QRestaurant restaurant,
            NumberExpression<Long> availableCapacity
    ) {
        BooleanBuilder predicates = new BooleanBuilder();
        predicates.and(reservation.timeSlotId.eq(timeSlot.id));
        predicates.and(timeSlot.sharedTableId.eq(sharedTable.id));
        predicates.and(sharedTable.restaurantId.eq(restaurant.id));
        predicates.and(restaurant.deletedAt.isNull());
        predicates.and(restaurant.status.eq(RestaurantStatus.ACTIVE));
        predicates.and(sharedTable.deletedAt.isNull());
        predicates.and(timeSlot.deletedAt.isNull());
        predicates.and(reservation.reservationStatus.in(ACTIVE_STATUSES));
        predicates.and(reservation.recruitmentStatus.eq(RecruitmentStatus.OPEN));

        if (StringUtils.hasText(request.keyword())) {
            String keyword = request.keyword().trim();
            predicates.and(restaurant.name.containsIgnoreCase(keyword)
                    .or(restaurant.keyword.containsIgnoreCase(keyword)));
        }
        if (request.date() != null || request.time() != null) {
            predicates.and(timeSlotCondition(timeSlot, request.date(), request.time()));
        }
        if (request.capacity() != null) {
            predicates.and(sharedTable.capacity.eq(request.capacity()));
        }
        if (request.minimumRemainingSeats() != null) {
            predicates.and(availableCapacity.goe(request.minimumRemainingSeats().longValue()));
        }
        return predicates;
    }

    private JPQLQuery<Long> currentParticipantCount(QReservation reservation) {
        QReservationParticipant participant = new QReservationParticipant("reservationSearchParticipant");
        return JPAExpressions
                .select(participant.partySize.sum().coalesce(0).longValue())
                .from(participant)
                .where(participant.reservationId.eq(reservation.id)
                        .and(participant.participationStatus.eq(ParticipationStatus.RESERVED)));
    }

    private JPQLQuery<Long> temporaryHeldCount(QTimeSlot timeSlot, Instant now) {
        QPayment payment = new QPayment("reservationSearchPayment");
        return JPAExpressions
                .select(payment.partySize.sum().coalesce(0).longValue())
                .from(payment)
                .where(payment.timeSlotId.eq(timeSlot.id)
                        .and(payment.status.eq(PaymentStatus.READY))
                        .and(payment.expiresAt.gt(now)));
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

    private OrderSpecifier<?>[] reservationOrderSpecifiers(
            Sort sort,
            QReservation reservation,
            QTimeSlot timeSlot,
            QSharedTable sharedTable,
            QRestaurant restaurant,
            JPQLQuery<Long> currentParticipantCount,
            NumberExpression<Long> availableCapacity
    ) {
        List<OrderSpecifier<?>> orderSpecifiers = new ArrayList<>();
        Order recentDirection = null;
        for (Sort.Order order : sort) {
            OrderSpecifier<?> orderSpecifier = reservationOrderSpecifier(
                    order, reservation, timeSlot, sharedTable, restaurant,
                    currentParticipantCount, availableCapacity);
            if (orderSpecifier != null) {
                orderSpecifiers.add(orderSpecifier);
            }
            if (order.getProperty().equals("createdAt") || order.getProperty().equals("recent")) {
                recentDirection = order.isAscending() ? Order.ASC : Order.DESC;
            }
        }
        if (orderSpecifiers.isEmpty()) {
            orderSpecifiers.add(timeSlot.startAt.asc());
            orderSpecifiers.add(reservation.id.asc());
        } else if (recentDirection != null) {
            orderSpecifiers.add(order(recentDirection, reservation.id));
        }
        return orderSpecifiers.toArray(OrderSpecifier[]::new);
    }

    private OrderSpecifier<?> reservationOrderSpecifier(
            Sort.Order order,
            QReservation reservation,
            QTimeSlot timeSlot,
            QSharedTable sharedTable,
            QRestaurant restaurant,
            JPQLQuery<Long> currentParticipantCount,
            NumberExpression<Long> availableCapacity
    ) {
        Order direction = order.isAscending() ? Order.ASC : Order.DESC;
        return switch (order.getProperty()) {
            case "reservationId" -> order(direction, reservation.id);
            case "restaurantId" -> order(direction, restaurant.id);
            case "restaurantName" -> order(direction, restaurant.name);
            case "sessionId" -> order(direction, timeSlot.id);
            case "tableId" -> order(direction, sharedTable.id);
            case "capacity" -> order(direction, sharedTable.capacity);
            case "startAt" -> order(direction, timeSlot.startAt);
            case "endAt" -> order(direction, timeSlot.endAt);
            case "createdAt", "recent" -> order(direction, reservation.createdAt);
            case "currentParticipantCount" -> new OrderSpecifier<>(direction, currentParticipantCount);
            case "availableCapacity" -> new OrderSpecifier<>(direction, availableCapacity);
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
