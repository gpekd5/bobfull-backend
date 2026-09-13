package com.bobfull.admin.repository;

import com.bobfull.admin.dto.AdminNoShowResult;
import com.bobfull.member.domain.entity.QMember;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QNoShowHistory;
import com.bobfull.reservation.domain.entity.QReservation;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.bobfull.restaurant.entity.QRestaurant;
import com.bobfull.sharedtable.entity.QSharedTable;
import com.bobfull.timeslot.entity.QTimeSlot;
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

/** ADMIN 전체 노쇼 현황 조회(§11-8)를 담당한다(Issue #134). */
@Repository
public class AdminNoShowRepositoryImpl implements AdminNoShowRepository {

    private final JPAQueryFactory queryFactory;

    public AdminNoShowRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    /**
     * 참여자별로 마지막 노쇼 처리(marked=true) 이력 1건만 반환한다.
     * 처리 → 해제 → 재처리가 반복되면 marked=true 이력이 여러 건 남을 수 있는데,
     * 이미 해제로 대체된 과거 이력까지 그대로 나열하면 "현재 노쇼 현황"에 같은 참여자가
     * 중복 노출된다(PR #136 리뷰 반영). JPQL은 "그룹별 최신 행" 조회에 윈도우 함수를
     * 지원하지 않아, 대상 이력을 평면으로 조회한 뒤 Java에서 참여자별 최신 건만 추리고
     * 수동으로 페이지네이션한다(NoShowQueryRepositoryImpl.findNoShowCustomers와 동일한 방식).
     */
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
