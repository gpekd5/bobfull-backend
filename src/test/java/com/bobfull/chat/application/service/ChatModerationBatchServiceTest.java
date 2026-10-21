package com.bobfull.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bobfull.chat.application.exception.BatchModerationException;
import com.bobfull.chat.application.port.AiModerationBatchPort;
import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ChatModerationBatchServiceTest {
    private static final ModerationResult SAFE = new ModerationResult(ModerationResultType.SAFE, Set.of(), RiskLevel.LOW);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatModerationRepository moderations = mock(ChatModerationRepository.class);
    private final Map<Long, ChatMessage> input = new HashMap<>();
    private final Map<Long, ChatModeration> saved = new HashMap<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicInteger nativeCalls = new AtomicInteger();
    private final List<List<AiModerationBatchPort.Item>> requests = new ArrayList<>();
    private Function<List<AiModerationBatchPort.Item>, List<AiModerationBatchPort.Output>> outputs = items ->
            items.stream().map(i -> new AiModerationBatchPort.Output(i.id(), SAFE)).toList();
    private final ChatModerationService moderation = new ChatModerationService(messages, moderations,
            content -> {
                nativeCalls.incrementAndGet();
                return new AiModerationResult(SAFE, "Fake", "test-model", 11L, 7L, 18L);
            }, new ModerationRulePolicy(), new SplitMessageCandidateGate(), Clock.systemUTC(), new ChatModerationMetrics(registry));
    private final ChatModerationBatchService service = new ChatModerationBatchService(moderation, items -> {
        requests.add(items);
        return new AiModerationBatchPort.BatchResult(outputs.apply(items), "Fake", "test-model");
    }, new ChatModerationBatchMetrics(registry), Clock.systemUTC());

    @BeforeEach
    void prepare() {
        when(messages.findById(any())).thenAnswer(call -> Optional.ofNullable(input.get(call.getArgument(0))));
        when(moderations.findByMessageId(any())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        when(moderations.saveAndFlush(any())).thenAnswer(call -> {
            ChatModeration row = call.getArgument(0);
            saved.put(row.getMessageId(), row);
            return row;
        });
    }

    @Test
    void Rule과_완료건을_제외한_한건은_Native로_처리한다() {
        // given
        message(1, "씨발");
        message(2, "이번 주 식사 일정 확인해요");
        service.analyze(List.of(1L));
        // when
        service.analyze(List.of(1L, 2L, 2L));
        // then
        assertThat(nativeCalls.get()).isEqualTo(1);
        assertThat(requests).isEmpty();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1L).getProvider()).isEqualTo("BOBFULL_RULE");
        assertThat(saved.get(2L).getPromptTokens()).isEqualTo(11L);
    }

    @Test
    void 열건은_중립ID로_역순응답을_매핑하고_Token을_복제하지_않는다() {
        // given
        List<Long> ids = range(10);
        outputs = items -> {
            var list = new ArrayList<>(items.stream().map(i -> new AiModerationBatchPort.Output(i.id(), SAFE)).toList());
            Collections.reverse(list);
            return list;
        };
        // when
        service.analyze(ids);
        // then
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).allSatisfy(item -> assertThat(item.id()).matches("m\\d{3}"));
        assertThat(saved).hasSize(10);
        assertThat(saved.values()).allSatisfy(row -> {
            assertThat(row.getPromptTokens()).isNull();
            assertThat(row.getTotalTokens()).isNull();
        });
        assertThat(registry.get("bobfull.ai.moderation.batch.fill.ratio").summary().totalAmount()).isEqualTo(1);
        assertThat(registry.get("bobfull.ai.moderation.e2e").tag("path", "batch").timer().count()).isEqualTo(10);
    }

    @Test
    void 부분_Validation_실패의_index는_Rule과_완료건_제외전_poll_index다() {
        // given
        range(4);
        service.analyze(List.of(1L));
        message(2, "씨발");
        outputs = items -> List.of(new AiModerationBatchPort.Output("m001", null),
                new AiModerationBatchPort.Output("m002", SAFE));
        // when / then
        assertThatThrownBy(() -> service.analyze(List.of(1L, 2L, 3L, 4L)))
                .isInstanceOfSatisfying(BatchModerationException.class, e -> assertThat(e.index()).isEqualTo(2));
        assertThat(saved.keySet()).containsExactlyInAnyOrder(1L, 2L, 4L);
        outputs = items -> items.stream().map(i -> new AiModerationBatchPort.Output(i.id(), SAFE)).toList();
        service.analyze(List.of(3L, 4L));
        assertThat(saved).hasSize(4);
        assertThat(nativeCalls.get()).isEqualTo(2);
    }

    @Test
    void 매핑오류는_일부결과도_저장하지_않는다() {
        // given
        List<Long> ids = range(2);
        outputs = items -> List.of(new AiModerationBatchPort.Output("m001", SAFE),
                new AiModerationBatchPort.Output("m001", SAFE));
        // when / then
        assertThatThrownBy(() -> service.analyze(ids)).isInstanceOf(BatchModerationException.class);
        assertThat(saved).isEmpty();
    }

    @Test
    void Rule로_걸러진_열건은_Provider를_호출하지_않는다() {
        // given
        List<Long> ids = range(10);
        ids.forEach(id -> message(id, "씨발"));
        // when
        service.analyze(ids);
        // then
        assertThat(requests).isEmpty();
        assertThat(nativeCalls.get()).isZero();
        assertThat(saved).hasSize(10);
    }

    @Test
    void 요청_실패는_미해결이며_정상_SAFE로_저장하지_않는다() {
        // given
        var ids = range(3);
        outputs = items -> { throw new IllegalStateException("FAKE_PROVIDER_FAILURE"); };
        // when / then
        assertThatThrownBy(() -> service.analyze(ids))
                .isInstanceOfSatisfying(BatchModerationException.class, e -> assertThat(e.index()).isZero());
        assertThat(saved).isEmpty();
        assertThat(nativeCalls.get()).isZero();
    }

    @Test
    void 기존_Split_Rule도_Batch_대상에서_제외한다() {
        // given
        message(1, "시");
        message(2, "발");
        ReflectionTestUtils.setField(input.get(2L), "chatRoomId", 1L);
        ReflectionTestUtils.setField(input.get(2L), "senderMemberId", 1L);
        when(messages.findRecentModerationContext(anyLong(), anyLong(), any(), anyLong(), any(), any()))
                .thenReturn(List.of(input.get(2L), input.get(1L)));
        // when
        service.analyze(List.of(2L));
        // then
        assertThat(saved.get(2L).getProvider()).isEqualTo("BOBFULL_RULE");
        assertThat(requests).isEmpty();
        assertThat(nativeCalls.get()).isZero();
    }

    @Test
    void Metric_등록실패가_실제_저장과_Provider_흐름을_깨지_않는다() {
        // given
        var failing = mock(io.micrometer.core.instrument.MeterRegistry.class);
        doThrow(new IllegalStateException("METRIC_FAILURE")).when(failing).summary(any());
        var tested = new ChatModerationBatchService(moderation, items ->
                new AiModerationBatchPort.BatchResult(outputs.apply(items), "Fake", "test-model"),
                new ChatModerationBatchMetrics(failing), Clock.systemUTC());
        // when
        tested.analyze(range(2));
        // then
        assertThat(saved).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unexpected", "null"})
    void 불완전한_ID계약은_모든_항목을_미해결로_둔다(String mode) {
        // given
        var ids = range(2);
        outputs = items -> switch (mode) {
            case "missing" -> List.of(new AiModerationBatchPort.Output("m001", SAFE));
            case "unexpected" -> List.of(new AiModerationBatchPort.Output("m001", SAFE),
                    new AiModerationBatchPort.Output("foreign", SAFE));
            default -> null;
        };
        // when / then
        assertThatThrownBy(() -> service.analyze(ids)).isInstanceOf(BatchModerationException.class);
        assertThat(saved).isEmpty();
    }

    @Test
    void Custom_Metric은_허용된_낮은_cardinality_tag만_사용한다() {
        // given / when
        service.analyze(range(2));
        // then
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getKey()).isIn("route", "prompt_version", "policy_version", "status", "stage", "path", "basis")));
    }

    @Test
    void 준비만으로_Rule은_저장하지만_LLM은_실행하지_않는다() {
        // given
        range(3);
        message(2, "씨발");
        // when
        var prepared = service.prepare(List.of(1L, 2L, 3L));
        // then
        assertThat(prepared).extracting(ChatModerationBatchService.PreparedItem::requiresProvider)
                .containsExactly(true, false, true);
        assertThat(saved.keySet()).containsExactly(2L);
        assertThat(requests).isEmpty();
        assertThat(nativeCalls.get()).isZero();
        service.analyzePrepared(prepared);
        assertThat(requests).hasSize(1).first().asList().hasSize(2);
        assertThat(saved).hasSize(3);
        verify(messages, times(1)).findById(1L);
        verify(messages, times(1)).findById(2L);
        verify(messages, times(1)).findById(3L);
    }

    @Test
    void 준비후_다른_Consumer가_완료한_항목은_재전송하지_않는다() {
        // given
        var prepared = service.prepare(range(3));
        moderation.analyze(2L);
        // when
        service.analyzePrepared(prepared);
        // then
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).extracting(AiModerationBatchPort.Item::content)
                .containsExactly(input.get(1L).getContent(), input.get(3L).getContent());
        assertThat(saved).hasSize(3);
        assertThat(nativeCalls.get()).isEqualTo(1);
    }

    @Test
    void 준비후_부분실패도_원래_입력_index와_항목실패_분류를_보존한다() {
        // given
        range(3);
        message(1, "씨발");
        var prepared = service.prepare(List.of(1L, 2L, 3L));
        outputs = items -> List.of(new AiModerationBatchPort.Output("m001", SAFE),
                new AiModerationBatchPort.Output("m002", null));
        // when / then
        assertThatThrownBy(() -> service.analyzePrepared(prepared))
                .isInstanceOfSatisfying(BatchModerationException.class, error -> {
                    assertThat(error.index()).isEqualTo(2);
                    assertThat(error.requestFailures()).isEmpty();
                });
        assertThat(saved.keySet()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void 준비후_매핑실패는_실제_미완료_요청그룹의_index만_보존한다() {
        // given
        var prepared = service.prepare(range(3));
        moderation.analyze(1L);
        outputs = items -> List.of(new AiModerationBatchPort.Output("duplicate", SAFE),
                new AiModerationBatchPort.Output("duplicate", SAFE));
        // when / then
        assertThatThrownBy(() -> service.analyzePrepared(prepared))
                .isInstanceOfSatisfying(BatchModerationException.class, error -> {
                    assertThat(error.requestFailures()).containsExactly(List.of(1, 2));
                    assertThat(error.failures()).containsOnlyKeys(1, 2);
                });
        assertThat(saved.keySet()).containsExactly(1L);
    }

    @Test
    void 서로다른_준비목록을_합쳐도_중복_메시지는_한번만_요청한다() {
        // given
        range(2);
        var prepared = new ArrayList<>(service.prepare(List.of(1L, 2L)));
        prepared.addAll(service.prepare(List.of(1L)));
        // when
        service.analyzePrepared(prepared);
        // then
        assertThat(requests).hasSize(1).first().asList().hasSize(2);
        assertThat(saved).hasSize(2);
    }

    @Test
    void 수집대기는_기존_분석_latency에_추가하지_않는다() throws Exception {
        // given
        long started = System.nanoTime();
        var prepared = service.prepare(range(2));
        Thread.sleep(300);
        // when
        service.analyzePrepared(prepared);
        long elapsed = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        // then: 새 대기는 별도 수집 지표/E2E로 측정하고 기존 분석 latency에서 제외한다.
        assertThat(saved.values()).allSatisfy(row -> assertThat(row.getLatencyMillis()).isLessThan(elapsed - 250));
    }

    private List<Long> range(int size) {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= size; i++) {
            message(i, "일정 확인 메시지 " + i);
            ids.add(i);
        }
        return ids;
    }

    private void message(long id, String content) {
        ChatMessage message = ChatMessage.create(id, id, id, content);
        ReflectionTestUtils.setField(message, "id", id);
        ReflectionTestUtils.setField(message, "createdAt", Instant.now().minusSeconds(1));
        input.put(id, message);
    }
}
