package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.member.domain.entity.QMember;
import com.bobfull.reservation.application.dto.NoShowCustomerResult;
import com.bobfull.reservation.application.dto.NoShowHistoryResult;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QNoShowHistory;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.sharedtable.entity.QSharedTable;
import com.bobfull.timeslot.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** §9-4·§9-5 노쇼 이력·집계 조회를 담당한다(Issue #48). */
@Repository
public class NoShowQueryRepositoryImpl implements NoShowQueryRepository {

    private final JPAQueryFactory queryFactory;

    public NoShowQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<NoShowHistoryResult> findHistoriesByReservationId(Long reservationId, Pageable pageable) {
        QNoShowHistory history = QNoShowHistory.noShowHistory;
        QReservationParticipant participant = QReservationParticipant.reservationParticipant;
        QMember member = QMember.member;

        List<NoShowHistoryResult> content = queryFactory
                .select(Projections.constructor(
                        NoShowHistoryResult.class,
                        history.id,
                        participant.id,
                        participant.memberId,
                        member.name,
                        participant.partySize,
                        history.marked,
                        history.processedByMemberId,
                        history.processedAt))
                .from(history)
                .join(participant).on(participant.id.eq(history.reservationParticipantId))
                .join(member).on(member.id.eq(participant.memberId))
                .where(participant.reservationId.eq(reservationId))
                .orderBy(history.processedAt.desc(), history.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(history.count())
                .from(history)
                .join(participant).on(participant.id.eq(history.reservationParticipantId))
                .where(participant.reservationId.eq(reservationId))
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public Page<NoShowCustomerResult> findNoShowCustomers(
            Long restaurantId, Instant startAt, Instant endAt, Pageable pageable
    ) {
        QReservationParticipant participant = QReservationParticipant.reservationParticipant;
        QReservation reservation = QReservation.reservation;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QNoShowHistory history = QNoShowHistory.noShowHistory;
        QMember member = QMember.member;

        BooleanBuilder predicates = new BooleanBuilder();
        predicates.and(sharedTable.restaurantId.eq(restaurantId));
        predicates.and(participant.participationStatus.eq(ParticipationStatus.NO_SHOW));
        predicates.and(history.marked.isTrue());
        if (startAt != null) {
            predicates.and(history.processedAt.goe(startAt));
        }
        if (endAt != null) {
            predicates.and(history.processedAt.lt(endAt));
        }

        List<Tuple> rows = queryFactory
                .select(participant.memberId, member.name, participant.id, participant.reservationId,
                        participant.partySize, history.processedAt)
                .from(history)
                .join(participant).on(participant.id.eq(history.reservationParticipantId))
                .join(reservation).on(reservation.id.eq(participant.reservationId))
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .join(member).on(member.id.eq(participant.memberId))
                .where(predicates)
                .fetch();

        Map<Long, List<Tuple>> rowsByMember = rows.stream()
                .collect(Collectors.groupingBy(row -> row.get(participant.memberId)));

        List<NoShowCustomerResult> allResults = rowsByMember.values().stream()
                .map(memberRows -> {
                    Tuple latest = memberRows.stream()
                            .max(Comparator.comparing(row -> row.get(history.processedAt)))
                            .orElseThrow();
                    long noShowCount = memberRows.stream()
                            .map(row -> row.get(participant.id))
                            .distinct()
                            .count();
                    return new NoShowCustomerResult(
                            latest.get(participant.memberId),
                            latest.get(member.name),
                            noShowCount,
                            latest.get(history.processedAt),
                            latest.get(participant.reservationId),
                            latest.get(participant.id),
                            latest.get(participant.partySize));
                })
                .sorted(Comparator.comparing(NoShowCustomerResult::latestNoShowAt).reversed())
                .toList();

        int total = allResults.size();
        int fromIndex = Math.min((int) pageable.getOffset(), total);
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), total);
        List<NoShowCustomerResult> pageContent = allResults.subList(fromIndex, toIndex);

        return new PageImpl<>(pageContent, pageable, total);
    }
}
