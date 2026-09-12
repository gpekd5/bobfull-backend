package com.bobfull.admin.repository;

import com.bobfull.admin.dto.AdminMemberResult;
import com.bobfull.common.security.MemberRole;
import com.bobfull.member.entity.QMember;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.QReservationParticipant;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

public class AdminMemberRepositoryImpl implements AdminMemberRepository {

    private final JPAQueryFactory queryFactory;

    public AdminMemberRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<AdminMemberResult> searchMembers(String keyword, MemberRole role, Boolean deleted, Pageable pageable) {
        QMember member = QMember.member;

        BooleanBuilder predicates = predicates(member, keyword, role, deleted);

        var content = queryFactory
                .select(projection(member))
                .from(member)
                .where(predicates)
                .orderBy(member.createdAt.desc(), member.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(member.id.count())
                .from(member)
                .where(predicates)
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public Optional<AdminMemberResult> findMemberDetail(Long memberId) {
        QMember member = QMember.member;
        AdminMemberResult result = queryFactory
                .select(projection(member))
                .from(member)
                .where(member.id.eq(memberId))
                .fetchFirst();
        return Optional.ofNullable(result);
    }

    private com.querydsl.core.types.Expression<AdminMemberResult> projection(QMember member) {
        return Projections.constructor(
                AdminMemberResult.class,
                member.id,
                member.email,
                member.name,
                member.phoneNumber,
                member.role,
                noShowCount(member),
                member.createdAt,
                member.deletedAt
        );
    }

    private com.querydsl.jpa.JPQLQuery<Long> noShowCount(QMember member) {
        QReservationParticipant participant = new QReservationParticipant("adminMemberNoShowParticipant");
        return JPAExpressions
                .select(participant.id.count())
                .from(participant)
                .where(participant.memberId.eq(member.id)
                        .and(participant.participationStatus.eq(ParticipationStatus.NO_SHOW)));
    }

    private BooleanBuilder predicates(QMember member, String keyword, MemberRole role, Boolean deleted) {
        BooleanBuilder predicates = new BooleanBuilder();
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            predicates.and(member.name.containsIgnoreCase(trimmed).or(member.email.containsIgnoreCase(trimmed)));
        }
        if (role != null) {
            predicates.and(member.role.eq(role));
        }
        if (deleted != null) {
            predicates.and(deleted ? member.deletedAt.isNotNull() : member.deletedAt.isNull());
        }
        return predicates;
    }
}
