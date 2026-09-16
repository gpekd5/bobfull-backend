package com.bobfull.admin.infrastructure.repository.query;

import com.bobfull.admin.application.result.AdminNoShowResult;
import com.bobfull.member.domain.entity.QMember;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QNoShowHistory;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.restaurant.restaurant.domain.entity.QRestaurant;
import com.bobfull.restaurant.sharedtable.domain.entity.QSharedTable;
import com.bobfull.restaurant.timeslot.domain.entity.QTimeSlot;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

// 관리자 노쇼 현황 조회를 구현한다.
@Repository
public class AdminNoShowRepositoryImpl implements AdminNoShowRepository {

    private final JPAQueryFactory queryFactory;

    public AdminNoShowRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    // 참여자별 최신 노쇼 처리 이력만 추려 현재 노쇼 현황을 페이지로 반환한다.
    @Override
    public Page<AdminNoShowResult> searchNoShows(Long memberId, Long restaurantId, Pageable pageable) {
        QNoShowHistory history = QNoShowHistory.noShowHistory;
        QReservationParticipant participant = QReservationParticipant.reservationParticipant;
        QReservation reservation = QReservation.reservation;
        QTimeSlot timeSlot = QTimeSlot.timeSlot;
        QSharedTable sharedTable = QSharedTable.sharedTable;
        QRestaurant restaurant = QRestaurant.restaurant;
        QMember member = QMember.member;

        BooleanBuilder predicates = new BooleanBuilder();
        predicates.and(history.marked.isTrue());
        // 노쇼 처리 후 해제된(RESERVED로 복귀한) 참여자의 과거 이력은 "현재 노쇼 현황"에서 제외한다.
        predicates.and(participant.participationStatus.eq(ParticipationStatus.NO_SHOW));
        if (memberId != null) {
            predicates.and(participant.memberId.eq(memberId));
        }
        if (restaurantId != null) {
            predicates.and(restaurant.id.eq(restaurantId));
        }

        List<Tuple> rows = queryFactory
                .select(history.id, participant.id, participant.memberId, member.name,
                        restaurant.id, restaurant.name, participant.reservationId,
                        participant.partySize, history.processedAt)
                .from(history)
                .join(participant).on(participant.id.eq(history.reservationParticipantId))
                .join(reservation).on(reservation.id.eq(participant.reservationId))
                .join(timeSlot).on(timeSlot.id.eq(reservation.timeSlotId))
                .join(sharedTable).on(sharedTable.id.eq(timeSlot.sharedTableId))
                .join(restaurant).on(restaurant.id.eq(sharedTable.restaurantId))
                .join(member).on(member.id.eq(participant.memberId))
                .where(predicates)
                .fetch();

        // JPQL로 그룹별 최신 행을 선택하기 어려워 평면 조회 후 최신 이력을 골라 페이지를 구성한다.
        Map<Long, List<Tuple>> rowsByParticipant = rows.stream()
                .collect(Collectors.groupingBy(row -> row.get(participant.id)));

        List<AdminNoShowResult> allResults = rowsByParticipant.values().stream()
                .map(participantRows -> participantRows.stream()
                        .max(Comparator.comparing(row -> row.get(history.processedAt)))
                        .orElseThrow())
                .map(latest -> new AdminNoShowResult(
                        latest.get(history.id),
                        latest.get(participant.memberId),
                        latest.get(member.name),
                        latest.get(restaurant.id),
                        latest.get(restaurant.name),
                        latest.get(participant.reservationId),
                        latest.get(participant.id),
                        latest.get(participant.partySize),
                        latest.get(history.processedAt)))
                .sorted(Comparator.comparing(AdminNoShowResult::processedAt).reversed()
                        .thenComparing(Comparator.comparing(AdminNoShowResult::noShowHistoryId).reversed()))
                .toList();

        int total = allResults.size();
        int fromIndex = Math.min((int) pageable.getOffset(), total);
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), total);
        List<AdminNoShowResult> pageContent = allResults.subList(fromIndex, toIndex);

        return new PageImpl<>(pageContent, pageable, total);
    }
}
