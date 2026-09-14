package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.application.dto.AiModerationResponse;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.application.service.ModerationRuleFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FakeAiModerationAdapterTest {

    @Test
    void 지연시간이_0이면_즉시_SAFE_결과를_반환한다() {
        FakeAiModerationAdapter adapter = new FakeAiModerationAdapter(0L, ModerationResultType.SAFE);

        AiModerationResponse response = adapter.analyze("안녕하세요");

        assertThat(response.result().result()).isEqualTo(ModerationResultType.SAFE);
        assertThat(response.result().riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(response.provider()).isEqualTo("Fake");
    }

    @Test
    void FLAGGED로_설정하면_HIGH_위험도와_비어있지_않은_category를_반환한다() {
        FakeAiModerationAdapter adapter = new FakeAiModerationAdapter(0L, ModerationResultType.FLAGGED);

        AiModerationResponse response = adapter.analyze("금지어");

        assertThat(response.result().result()).isEqualTo(ModerationResultType.FLAGGED);
        assertThat(response.result().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(response.result().categories()).containsExactly(ModerationCategory.PROFANITY);
    }

    @Test
    void FLAGGED_결과는_ModerationResultValidator_계약을_위반하지_않고_ChatModerationService_경로를_통과한다() {
        ChatMessageRepository messages = Mockito.mock(ChatMessageRepository.class);
        ChatModerationRepository moderations = Mockito.mock(ChatModerationRepository.class);
        FakeAiModerationAdapter adapter = new FakeAiModerationAdapter(0L, ModerationResultType.FLAGGED);
        ChatModerationService service = new ChatModerationService(messages, moderations, adapter, new ModerationRuleFilter(), new com.bobfull.chat.application.service.SplitMessageCandidateGate(),
                Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC));
        ChatMessage message = ChatMessage.create(1L, 2L, 3L, "금지어 포함 메시지");
        org.springframework.test.util.ReflectionTestUtils.setField(message, "id", 100L);
        Mockito.when(moderations.findByMessageId(100L)).thenReturn(Optional.empty());
        Mockito.when(messages.findById(100L)).thenReturn(Optional.of(message));

        service.analyze(100L);

        ArgumentCaptor<ChatModeration> captor = ArgumentCaptor.forClass(ChatModeration.class);
        Mockito.verify(moderations).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(ModerationResultType.FLAGGED);
        assertThat(captor.getValue().getCategories()).containsExactly(ModerationCategory.PROFANITY);
    }

    @Test
    void FORCE_FAIL_MARKER를_포함한_content는_강제로_실패한다() {
        FakeAiModerationAdapter adapter = new FakeAiModerationAdapter(0L, ModerationResultType.SAFE);

        assertThatThrownBy(() -> adapter.analyze(FakeAiModerationAdapter.FORCE_FAIL_MARKER + " 실패 유도"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 설정한_지연시간만큼_실제로_대기한다() {
        long latencyMs = 100L;
        FakeAiModerationAdapter adapter = new FakeAiModerationAdapter(latencyMs, ModerationResultType.SAFE);

        long start = System.nanoTime();
        adapter.analyze("지연 확인");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(latencyMs);
    }
}
