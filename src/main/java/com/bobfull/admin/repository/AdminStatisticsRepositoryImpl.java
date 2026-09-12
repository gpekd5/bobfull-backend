package com.bobfull.admin.repository;

import com.bobfull.admin.dto.AdminMemberNoShowRateResult;
import com.bobfull.admin.dto.AdminRestaurantStatisticsResult;
import com.bobfull.member.entity.QMember;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.restaurant.entity.QRestaurant;
import com.bobfull.sharedtable.entity.QSharedTable;
import com.bobfull.timeslot.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** ADMIN 운영 통계(§11-10, §11-11)의 집계 조회를 담당한다(Issue #49). */
@Repository
public class AdminStatisticsRepositoryImpl implements AdminStatisticsRepository {

    private final JPAQueryFactory queryFactory;

    public AdminStatisticsRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<AdminRestaurantStatisticsResult> aggregateRestaurantStatistics(
            Instant startAt, Instant endAt, Pageable pageable
    ) {
        QReservation reservation = QReservation.reservation;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QRestaurant restaurant = QRestaurant.restaurant;

        BooleanBuilder predicates = new BooleanBuilder();
        if (startAt != null) {
            predicates.and(timeSlot.startAt.goe(startAt));
        }
        if (endAt != null) {
            predicates.and(timeSlot.startAt.lt(endAt));
        }

        NumberExpression<Long> confirmedCount = new CaseBuilder()
                .when(reservation.reservationStatus.eq(ReservationStatus.CONFIRMED)).then(1L).otherwise(0L)
                .sum();

        List<AdminRestaurantStatisticsResult> content = queryFactory
                .select(Projections.constructor(
                        AdminRestaurantStatisticsResult.class,
                        restaurant.id,
                        restaurant.name,
                        reservation.id.count(),
                        confirmedCount
                ))
                .from(reservation)
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .join(restaurant).on(restaurant.id.eq(sharedTable.restaurantId))
                .where(predicates)
                .groupBy(restaurant.id, restaurant.name)
                .orderBy(restaurant.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(restaurant.id.countDistinct())
                .from(reservation)
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .join(restaurant).on(restaurant.id.eq(sharedTable.restaurantId))
                .where(predicates)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public Page<AdminMemberNoShowRateResult> aggregateMemberNoShowRates(Pageable pageable) {
        QReservationParticipant participant = QReservationParticipant.reservationParticipant;
        QMember member = QMember.member;

        List<ParticipationStatus> countedStatuses = List.of(ParticipationStatus.RESERVED, ParticipationStatus.NO_SHOW);

        NumberExpression<Long> noShowCount = new CaseBuilder()
                .when(participant.participationStatus.eq(ParticipationStatus.NO_SHOW)).then(1L).otherwise(0L)
                .sum();

        List<AdminMemberNoShowRateResult> content = queryFactory
                .select(Projections.constructor(
                        AdminMemberNoShowRateResult.class,
                        member.id,
                        member.name,
                        participant.id.count(),
                        noShowCount
                ))
                .from(participant)
                .join(member).on(member.id.eq(participant.memberId))
                .where(participant.participationStatus.in(countedStatuses))
                .groupBy(member.id, member.name)
                .orderBy(noShowCount.desc(), member.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(participant.memberId.countDistinct())
                .from(participant)
                .where(participant.participationStatus.in(countedStatuses))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
