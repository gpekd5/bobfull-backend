package com.bobfull.admin.infrastructure.repository.query;

import com.bobfull.admin.presentation.response.AdminMemberModerationEvidenceResponse;
import com.bobfull.admin.application.model.MemberModerationReviewStatus;
import com.bobfull.admin.application.result.MemberModerationSummaryResult;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.domain.entity.QChatMessage;
import com.bobfull.chat.domain.entity.QChatModeration;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.EnumPath;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

// ChatModeration과 ChatMessage를 조합해 관리자용 회원별 검토 신호를 집계한다.
@Repository
public class MemberModerationQueryRepositoryImpl implements MemberModerationQueryRepository {

    private static final long REVIEW_TARGET_THRESHOLD = 3L;

    private final JPAQueryFactory queryFactory;

    public MemberModerationQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<MemberModerationSummaryResult> findMemberSummaries(
            MemberModerationReviewStatus reviewStatus, Pageable pageable) {
        QChatModeration moderation = QChatModeration.chatModeration;
        QChatMessage message = QChatMessage.chatMessage;
        EnumPath<ModerationCategory> category = Expressions.enumPath(ModerationCategory.class, "moderationCategory");
        NumberExpression<Long> reviewTargetCount = distinctMessageCountForRisk(moderation, RiskLevel.MEDIUM, RiskLevel.HIGH);

        List<Tuple> rows = queryFactory
                .select(message.senderMemberId,
                        distinctMessageCountForCategory(moderation, category, ModerationCategory.PROFANITY),
                        distinctMessageCountForCategory(moderation, category, ModerationCategory.PERSONAL_INFORMATION),
                        distinctMessageCountForCategory(moderation, category, ModerationCategory.SPAM),
                        moderation.messageId.countDistinct(), reviewTargetCount, moderation.analyzedAt.max())
                .from(moderation)
                .join(message).on(message.id.eq(moderation.messageId))
                .leftJoin(moderation.categories, category)
                .where(moderation.status.eq(ModerationProcessingStatus.FLAGGED))
                .groupBy(message.senderMemberId)
                .having(reviewStatusPredicate(reviewStatus, reviewTargetCount))
                .orderBy(moderation.analyzedAt.max().desc(), message.senderMemberId.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(message.senderMemberId.countDistinct())
                .from(moderation)
                .join(message).on(message.id.eq(moderation.messageId))
                .where(moderation.status.eq(ModerationProcessingStatus.FLAGGED))
                .groupBy(message.senderMemberId)
                .having(reviewStatusPredicate(reviewStatus, reviewTargetCount))
                .fetchCount();

        return new PageImpl<>(rows.stream().map(row -> toSummary(row, message, moderation, category)).toList(),
                pageable, total);
    }

    @Override
    public Optional<MemberModerationSummaryResult> findMemberSummary(Long memberId) {
        QChatModeration moderation = QChatModeration.chatModeration;
        QChatMessage message = QChatMessage.chatMessage;
        EnumPath<ModerationCategory> category = Expressions.enumPath(ModerationCategory.class, "moderationCategory");
        Tuple row = queryFactory
                .select(message.senderMemberId,
                        distinctMessageCountForCategory(moderation, category, ModerationCategory.PROFANITY),
                        distinctMessageCountForCategory(moderation, category, ModerationCategory.PERSONAL_INFORMATION),
                        distinctMessageCountForCategory(moderation, category, ModerationCategory.SPAM),
                        moderation.messageId.countDistinct(),
                        distinctMessageCountForRisk(moderation, RiskLevel.MEDIUM, RiskLevel.HIGH), moderation.analyzedAt.max())
                .from(moderation)
                .join(message).on(message.id.eq(moderation.messageId))
                .leftJoin(moderation.categories, category)
                .where(moderation.status.eq(ModerationProcessingStatus.FLAGGED)
                        .and(message.senderMemberId.eq(memberId)))
                .groupBy(message.senderMemberId)
                .fetchFirst();
        return Optional.ofNullable(row).map(found -> toSummary(found, message, moderation, category));
    }

    @Override
    public List<AdminMemberModerationEvidenceResponse> findFlaggedEvidences(Long memberId) {
        QChatModeration moderation = QChatModeration.chatModeration;
        QChatMessage message = QChatMessage.chatMessage;
        return queryFactory
                .select(moderation, message)
                .from(moderation)
                .join(message).on(message.id.eq(moderation.messageId))
                .where(moderation.status.eq(ModerationProcessingStatus.FLAGGED)
                        .and(message.senderMemberId.eq(memberId)))
                .orderBy(moderation.analyzedAt.desc(), moderation.messageId.desc())
                .fetch()
                .stream()
                .map(row -> new AdminMemberModerationEvidenceResponse(
                        row.get(moderation).getMessageId(), row.get(message).getContent(), row.get(moderation).getCategories(),
                        row.get(moderation).getRiskLevel(), isReviewTarget(row.get(moderation).getRiskLevel()),
                        row.get(message).getCreatedAt(), row.get(moderation).getAnalyzedAt()))
                .toList();
    }

    @Override
    public Map<RiskLevel, Long> findRiskCounts(Long memberId) {
        QChatModeration moderation = QChatModeration.chatModeration;
        QChatMessage message = QChatMessage.chatMessage;
        return queryFactory
                .select(moderation.riskLevel, moderation.messageId.countDistinct())
                .from(moderation)
                .join(message).on(message.id.eq(moderation.messageId))
                .where(moderation.status.eq(ModerationProcessingStatus.FLAGGED)
                        .and(message.senderMemberId.eq(memberId)))
                .groupBy(moderation.riskLevel)
                .fetch()
                .stream()
                .collect(Collectors.toMap(row -> row.get(moderation.riskLevel),
                        row -> value(row.get(moderation.messageId.countDistinct()))));
    }

    private NumberExpression<Long> distinctMessageCountForCategory(
            QChatModeration moderation, EnumPath<ModerationCategory> category, ModerationCategory expected) {
        return new CaseBuilder().when(category.eq(expected)).then(moderation.messageId).otherwise((Long) null).countDistinct();
    }

    private NumberExpression<Long> distinctMessageCountForRisk(QChatModeration moderation, RiskLevel... riskLevels) {
        return new CaseBuilder().when(moderation.riskLevel.in(riskLevels)).then(moderation.messageId).otherwise((Long) null).countDistinct();
    }

    private com.querydsl.core.types.Predicate reviewStatusPredicate(
            MemberModerationReviewStatus reviewStatus, NumberExpression<Long> reviewTargetCount) {
        if (reviewStatus == MemberModerationReviewStatus.REVIEW_REQUIRED) {
            return reviewTargetCount.goe(REVIEW_TARGET_THRESHOLD);
        }
        if (reviewStatus == MemberModerationReviewStatus.NORMAL) {
            return reviewTargetCount.lt(REVIEW_TARGET_THRESHOLD);
        }
        return null;
    }

    private MemberModerationSummaryResult toSummary(
            Tuple row, QChatMessage message, QChatModeration moderation, EnumPath<ModerationCategory> category) {
        return new MemberModerationSummaryResult(
                row.get(message.senderMemberId), value(row.get(distinctMessageCountForCategory(moderation, category, ModerationCategory.PROFANITY))),
                value(row.get(distinctMessageCountForCategory(moderation, category, ModerationCategory.PERSONAL_INFORMATION))),
                value(row.get(distinctMessageCountForCategory(moderation, category, ModerationCategory.SPAM))),
                value(row.get(moderation.messageId.countDistinct())),
                value(row.get(distinctMessageCountForRisk(moderation, RiskLevel.MEDIUM, RiskLevel.HIGH))),
                row.get(moderation.analyzedAt.max()));
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private boolean isReviewTarget(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.MEDIUM || riskLevel == RiskLevel.HIGH;
    }
}
