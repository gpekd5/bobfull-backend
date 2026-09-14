package com.bobfull.admin.infrastructure.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.admin.presentation.dto.MemberModerationReviewStatus;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
@Import(MemberModerationQueryRepositoryImpl.class)
class MemberModerationQueryRepositoryImplTest {

    private static final Instant ANALYZED_AT = Instant.parse("2026-08-11T00:00:00Z");

    @Autowired private MemberModerationQueryRepository repository;
    @Autowired private ChatMessageRepository messages;
    @Autowired private ChatModerationRepository moderations;

    @Test
    void LOW만_세건이면_NORMAL이고_total에는_포함되지만_reviewTarget에는_포함되지_않는다() {
        Long memberId = 1L;
        flagged(memberId, RiskLevel.LOW, ModerationCategory.PROFANITY);
        flagged(memberId, RiskLevel.LOW, ModerationCategory.SPAM);
        flagged(memberId, RiskLevel.LOW, ModerationCategory.PERSONAL_INFORMATION);

        var result = repository.findMemberSummary(memberId).orElseThrow();

        assertThat(result.totalFlaggedCount()).isEqualTo(3);
        assertThat(result.reviewTargetCount()).isZero();
    }

    @Test
    void MEDIUM_HIGH_세건부터_REVIEW_REQUIRED_목록에_포함된다() {
        Long normalMemberId = 2L;
        flagged(normalMemberId, RiskLevel.MEDIUM, ModerationCategory.PROFANITY);
        flagged(normalMemberId, RiskLevel.MEDIUM, ModerationCategory.PROFANITY);
        Long reviewRequiredMemberId = 3L;
        flagged(reviewRequiredMemberId, RiskLevel.MEDIUM, ModerationCategory.PROFANITY);
        flagged(reviewRequiredMemberId, RiskLevel.MEDIUM, ModerationCategory.SPAM);
        flagged(reviewRequiredMemberId, RiskLevel.HIGH, ModerationCategory.PERSONAL_INFORMATION);
        Long aboveThresholdMemberId = 4L;
        flagged(aboveThresholdMemberId, RiskLevel.MEDIUM, ModerationCategory.PROFANITY);
        flagged(aboveThresholdMemberId, RiskLevel.MEDIUM, ModerationCategory.SPAM);
        flagged(aboveThresholdMemberId, RiskLevel.HIGH, ModerationCategory.PERSONAL_INFORMATION);
        flagged(aboveThresholdMemberId, RiskLevel.HIGH, ModerationCategory.PROFANITY);

        var reviewRequired = repository.findMemberSummaries(
                MemberModerationReviewStatus.REVIEW_REQUIRED, PageRequest.of(0, 20));

        assertThat(repository.findMemberSummary(normalMemberId).orElseThrow().reviewTargetCount()).isEqualTo(2);
        assertThat(repository.findMemberSummary(reviewRequiredMemberId).orElseThrow().reviewTargetCount()).isEqualTo(3);
        assertThat(repository.findMemberSummary(aboveThresholdMemberId).orElseThrow().reviewTargetCount()).isEqualTo(4);
        assertThat(reviewRequired.getContent()).extracting(result -> result.memberId())
                .containsExactlyInAnyOrder(reviewRequiredMemberId, aboveThresholdMemberId);
        assertThat(repository.findMemberSummaries(null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(3);
    }

    @Test
    void 다중_category_메시지는_category별_한건씩_집계하고_메시지_합계는_중복하지_않는다() {
        Long memberId = 4L;
        flagged(memberId, RiskLevel.HIGH, ModerationCategory.PROFANITY, ModerationCategory.SPAM);

        var result = repository.findMemberSummary(memberId).orElseThrow();

        assertThat(result.profanityCount()).isEqualTo(1);
        assertThat(result.spamCount()).isEqualTo(1);
        assertThat(result.totalFlaggedCount()).isEqualTo(1);
        assertThat(result.reviewTargetCount()).isEqualTo(1);
    }

    @Test
    void SAFE와_ANALYSIS_FAILED는_회원별_집계와_근거에서_제외된다() {
        Long memberId = 5L;
        flagged(memberId, RiskLevel.HIGH, ModerationCategory.SPAM);
        ChatMessage safeMessage = messages.save(ChatMessage.create(1L, memberId, memberId, "안전 메시지"));
        moderations.save(ChatModeration.completed(safeMessage.getId(), ModerationResultType.SAFE,
                EnumSet.noneOf(ModerationCategory.class), RiskLevel.LOW, "OpenAI", "gpt-4o-mini",
                "moderation-prompt-v2", "moderation-policy-v1", 10L, 1L, 1L, 2L, ANALYZED_AT));
        ChatMessage failedMessage = messages.save(ChatMessage.create(1L, memberId, memberId, "실패 메시지"));
        moderations.save(ChatModeration.failed(failedMessage.getId(), "OpenAI", "gpt-4o-mini",
                "moderation-prompt-v2", "moderation-policy-v1", 10L, ANALYZED_AT, "OPENAI_TIMEOUT"));

        var summary = repository.findMemberSummary(memberId).orElseThrow();

        assertThat(summary.totalFlaggedCount()).isEqualTo(1);
        assertThat(summary.reviewTargetCount()).isEqualTo(1);
        assertThat(repository.findFlaggedEvidences(memberId)).hasSize(1);
    }

    @Test
    void REVIEW_REQUIRED_회원_스물한명은_DB_집계후_정확하게_페이지네이션된다() {
        for (long memberId = 10; memberId <= 30; memberId++) {
            flagged(memberId, RiskLevel.MEDIUM, ModerationCategory.PROFANITY);
            flagged(memberId, RiskLevel.MEDIUM, ModerationCategory.PROFANITY);
            flagged(memberId, RiskLevel.HIGH, ModerationCategory.SPAM);
        }

        var page = repository.findMemberSummaries(MemberModerationReviewStatus.REVIEW_REQUIRED, PageRequest.of(1, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(21);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    private void flagged(Long memberId, RiskLevel riskLevel, ModerationCategory... categories) {
        ChatMessage message = messages.save(ChatMessage.create(1L, memberId, memberId, "검토 메시지"));
        moderations.save(ChatModeration.completed(message.getId(), ModerationResultType.FLAGGED,
                EnumSet.copyOf(java.util.List.of(categories)), riskLevel, "OpenAI", "gpt-4o-mini",
                "moderation-prompt-v2", "moderation-policy-v1", 10L, 1L, 1L, 2L, ANALYZED_AT));
    }
}
