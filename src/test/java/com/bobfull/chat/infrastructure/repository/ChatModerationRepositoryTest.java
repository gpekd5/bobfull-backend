package com.bobfull.chat.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
class ChatModerationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    @Autowired private ChatModerationRepository moderations;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void 같은_실패행을_읽은_두_갱신중_늦은_갱신은_낙관락으로_거절된다() {
        transactions.executeWithoutResult(status -> moderations.saveAndFlush(failed(100L)));
        ChatModeration first = transactions.execute(status -> moderations.findByMessageId(100L).orElseThrow());
        ChatModeration staleSecond = transactions.execute(status -> moderations.findByMessageId(100L).orElseThrow());

        first.complete(ModerationResultType.FLAGGED, EnumSet.of(ModerationCategory.SPAM), RiskLevel.HIGH,
                "OpenAI", "gpt-4o-mini", "moderation-prompt-v2", "moderation-policy-v1", 10L, 1L, 2L, 3L, NOW);
        transactions.executeWithoutResult(status -> moderations.saveAndFlush(first));

        staleSecond.fail("OpenAI", "NOT_MEASURED", "moderation-prompt-v2", "moderation-policy-v1", 0L, NOW, "OPENAI_TIMEOUT");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> moderations.saveAndFlush(staleSecond)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        ModerationProcessingStatus finalStatus = transactions.execute(status -> moderations.findByMessageId(100L).orElseThrow().getStatus());
        assertThat(finalStatus).isEqualTo(ModerationProcessingStatus.FLAGGED);
    }

    private static ChatModeration failed(Long messageId) {
        return ChatModeration.failed(messageId, "OpenAI", "NOT_MEASURED", "moderation-prompt-v2", "moderation-policy-v1", 0L, NOW,
                "OPENAI_TIMEOUT");
    }
}
