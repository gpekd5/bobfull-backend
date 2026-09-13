package com.bobfull.admin.repository;

import com.bobfull.admin.dto.AdminReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.restaurant.restaurant.domain.entity.QRestaurant;
import com.bobfull.restaurant.sharedtable.domain.entity.QSharedTable;
import com.bobfull.restaurant.timeslot.domain.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class AdminReservationRepositoryImpl implements AdminReservationRepository {

    private final JPAQueryFactory queryFactory;

    public AdminReservationRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<AdminReservationResult> searchReservations(
            ReservationStatus reservationStatus, Instant startAt, Instant endAt, Pageable pageable
    ) {
        QReservation reservation = QReservation.reservation;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QRestaurant restaurant = QRestaurant.restaurant;

        JPQLQuery<Long> currentParticipantCount = currentParticipantCount(reservation);

        BooleanBuilder predicates = predicates(reservation, timeSlot, reservationStatus, startAt, endAt);

        var content = queryFactory
                .select(Projections.constructor(
                        AdminReservationResult.class,
                        reservation.id,
                        restaurant.id,
                        restaurant.name,
                        reservation.creatorMemberId,
                        timeSlot.startAt,
                        reservation.reservationStatus,
                        reservation.recruitmentStatus,
                        currentParticipantCount,
                        sharedTable.capacity
                ))
                .from(reservation)
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .join(restaurant).on(restaurant.id.eq(sharedTable.restaurantId))
                .where(predicates)
                .orderBy(timeSlot.startAt.desc(), reservation.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(reservation.id.count())
                .from(reservation)
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .where(predicates)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private BooleanBuilder predicates(
            QReservation reservation, QTimeSlot timeSlot,
            ReservationStatus reservationStatus, Instant startAt, Instant endAt
    ) {
        BooleanBuilder predicates = new BooleanBuilder();
        if (reservationStatus != null) {
            predicates.and(reservation.reservationStatus.eq(reservationStatus));
        }
        if (startAt != null) {
            predicates.and(timeSlot.startAt.goe(startAt));
        }
        if (endAt != null) {
            predicates.and(timeSlot.startAt.lt(endAt));
        }
        return predicates;
    }

    private JPQLQuery<Long> currentParticipantCount(QReservation reservation) {
        QReservationParticipant participant = new QReservationParticipant("adminReservationParticipant");
        return JPAExpressions
                .select(participant.partySize.sum().coalesce(0).longValue())
                .from(participant)
                .where(participant.reservationId.eq(reservation.id)
                        .and(participant.participationStatus.eq(ParticipationStatus.RESERVED)));
    }
}
