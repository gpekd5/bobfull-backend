package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.entity.QPayment;
import com.bobfull.reservation.application.dto.OwnerReservationResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.restaurant.sharedtable.domain.entity.QSharedTable;
import com.bobfull.restaurant.timeslot.domain.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * OWNER의 식당별 예약 목록 조회를 담당한다(Issue #147 §6-11).
 * currentParticipantCount는 취소 접수(CANCEL_REQUESTED)도 환불 완료 전까지 좌석을 점유한 것으로
 * 집계하는 최신 계약(Issue #44, {@code AvailableCapacityCalculator}와 동일)을 따른다.
 */
public class OwnerReservationRepositoryImpl implements OwnerReservationRepository {

    private static final List<ParticipationStatus> OCCUPYING_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);

    private final JPAQueryFactory queryFactory;

    public OwnerReservationRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<OwnerReservationResult> searchOwnerReservations(
            Long restaurantId,
            ReservationStatus reservationStatus,
            Instant startAt,
            Instant endAt,
            Instant now,
            Pageable pageable
    ) {
        QReservation reservation = QReservation.reservation;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;
        QSharedTable sharedTable = QSharedTable.sharedTable;

        JPQLQuery<Long> currentParticipantCount = currentParticipantCount(reservation);
        JPQLQuery<Long> temporaryHeldCount = temporaryHeldCount(timeSlot, now);

        BooleanBuilder predicates = predicates(restaurantId, reservationStatus, startAt, endAt, reservation, timeSlot, sharedTable);

        List<OwnerReservationResult> content = queryFactory
                .select(Projections.constructor(
                        OwnerReservationResult.class,
                        reservation.id,
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
                .from(reservation)
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .where(predicates)
                .orderBy(timeSlot.startAt.asc(), reservation.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(reservation.id.count())
                .from(reservation)
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .where(predicates)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private BooleanBuilder predicates(
            Long restaurantId,
            ReservationStatus reservationStatus,
            Instant startAt,
            Instant endAt,
            QReservation reservation,
            QTimeSlot timeSlot,
            QSharedTable sharedTable
    ) {
        BooleanBuilder predicates = new BooleanBuilder();
        predicates.and(sharedTable.restaurantId.eq(restaurantId));
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
        QReservationParticipant participant = new QReservationParticipant("ownerReservationParticipant");
        return JPAExpressions
                .select(participant.partySize.sum().coalesce(0).longValue())
                .from(participant)
                .where(participant.reservationId.eq(reservation.id)
                        .and(participant.participationStatus.in(OCCUPYING_STATUSES)));
    }

    private JPQLQuery<Long> temporaryHeldCount(QTimeSlot timeSlot, Instant now) {
        QPayment payment = new QPayment("ownerReservationPayment");
        return JPAExpressions
                .select(payment.partySize.sum().coalesce(0).longValue())
                .from(payment)
                .where(payment.timeSlotId.eq(timeSlot.id)
                        .and(payment.status.eq(PaymentStatus.READY))
                        .and(payment.expiresAt.gt(now)));
    }
}
