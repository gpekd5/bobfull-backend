package com.bobfull.reservation.infrastructure.repository.query;

import com.bobfull.payment.domain.entity.QPayment;
import com.bobfull.reservation.application.result.MyReservationResult;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.restaurant.restaurant.domain.entity.QRestaurant;
import com.bobfull.restaurant.sharedtable.domain.entity.QSharedTable;
import com.bobfull.restaurant.timeslot.domain.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class MyReservationQueryRepositoryImpl implements MyReservationQueryRepository {

    private final JPAQueryFactory queryFactory;

    public MyReservationQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<MyReservationResult> searchMyReservations(Long memberId, ReservationStatus reservationStatus, Pageable pageable) {
        QReservationParticipant participant = QReservationParticipant.reservationParticipant;
        QReservation reservation = QReservation.reservation;

        BooleanBuilder predicates = new BooleanBuilder();
        predicates.and(participant.memberId.eq(memberId));
        if (reservationStatus != null) {
            predicates.and(reservation.reservationStatus.eq(reservationStatus));
        }

        List<MyReservationResult> content = selectMyReservation()
                .where(predicates)
                .orderBy(participant.createdAt.desc(), participant.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(participant.id.count())
                .from(participant)
                .join(reservation).on(reservation.id.eq(participant.reservationId))
                .where(predicates)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public Optional<MyReservationResult> findMyReservationDetail(Long memberId, Long reservationId) {
        QReservationParticipant participant = QReservationParticipant.reservationParticipant;
        QReservation reservation = QReservation.reservation;

        MyReservationResult result = selectMyReservation()
                .where(participant.memberId.eq(memberId), reservation.id.eq(reservationId))
                .fetchFirst();
        return Optional.ofNullable(result);
    }

    private com.querydsl.jpa.impl.JPAQuery<MyReservationResult> selectMyReservation() {
        QReservationParticipant participant = QReservationParticipant.reservationParticipant;
        QReservation reservation = QReservation.reservation;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QRestaurant restaurant = QRestaurant.restaurant;
        QPayment payment = QPayment.payment;

        return queryFactory
                .select(Projections.constructor(
                        MyReservationResult.class,
                        reservation.id,
                        restaurant.id,
                        restaurant.name,
                        timeSlot.id,
                        timeSlot.startAt,
                        timeSlot.endAt,
                        reservation.reservationStatus,
                        reservation.recruitmentStatus,
                        participant.id,
                        participant.partySize,
                        participant.participationStatus,
                        payment.status,
                        payment.paymentId
                ))
                .from(participant)
                .join(reservation).on(reservation.id.eq(participant.reservationId))
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .join(restaurant).on(restaurant.id.eq(sharedTable.restaurantId))
                .join(payment).on(payment.reservationParticipantId.eq(participant.id));
    }
}
