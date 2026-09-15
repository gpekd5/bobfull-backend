package com.bobfull.chat.application.service;

import com.bobfull.chat.application.exception.ModerationAnalysisException;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.infrastructure.ai.ModerationPrompt;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.common.exception.CustomException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** ChatMessage를 짧게 조회한 뒤 트랜잭션 밖에서 AI를 호출하고 결과만 영속화한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModerationService {

    private static final String RULE_PROVIDER = "BOBFULL_RULE";
    private static final String RULE_MODEL = "rule-filter-v1";
    private static final String RULE_PROMPT_VERSION = "NO_LLM";
    private final ChatMessageRepository messages;
    private final ChatModerationRepository moderations;
    private final AiModerationPort aiModerationPort;
    private final ModerationRulePolicy rulePolicy;
    private final SplitMessageCandidateGate splitCandidateGate;
    private final Clock clock;

    public void analyze(Long messageId) {
        ChatModeration existing = moderations.findByMessageId(messageId).orElse(null);
        if (existing != null && existing.isCompleted()) {
            log.info("event=CHAT_MODERATION_SKIPPED messageId={} status={}", messageId, existing.getStatus());
            return;
        }
        ChatMessage message = messages.findById(messageId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_MESSAGE_ID_NOT_FOUND));
        long startedAt = System.nanoTime();
        try {
            AnalysisResponse analysis = analyzeMessage(message);
            ModerationResultValidator.validate(
                    analysis.response() == null ? null : analysis.response().result());
            persistCompleted(
                    messageId,
                    existing,
                    analysis.response(),
                    analysis.promptVersion(),
                    elapsedMillis(startedAt));
        } catch (ModerationAnalysisException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            String errorCode = exception.getClass().getSimpleName();
            throw new ModerationAnalysisException(errorCode, exception);
        }
    }

    private AnalysisResponse analyzeMessage(ChatMessage message) {
        var singleMessageRule = rulePolicy.clearFlagged(message.getContent());
        if (singleMessageRule.isPresent()) {
            return ruleAnalysis(singleMessageRule.get());
        }
        SplitMessageContext context = recentSplitContext(message);
        if (context == null) {
            return providerAnalysis(
                    aiModerationPort.analyze(message.getContent()),
                    ModerationPrompt.PROMPT_VERSION);
        }
        var splitRule = rulePolicy.clearSplitFlagged(context.recentCanonicalCandidates());
        if (splitRule.isPresent()) {
            return ruleAnalysis(splitRule.get());
        }
        return providerAnalysis(
                aiModerationPort.analyze(message.getContent()),
                ModerationPrompt.PROMPT_VERSION);
    }

    private SplitMessageContext recentSplitContext(ChatMessage current) {
        if (!splitCandidateGate.mayNeedContext(current)) {
            return null;
        }
        List<ChatMessage> recentDescending = messages.findRecentModerationContext(
                current.getChatRoomId(),
                current.getSenderMemberId(),
                current.getCreatedAt(),
                current.getId(),
                current.getCreatedAt().minus(SplitMessageCandidateGate.CONTEXT_WINDOW),
                PageRequest.of(0, SplitMessageCandidateGate.RECENT_MESSAGE_LIMIT));
        List<ChatMessage> recent = new ArrayList<>(recentDescending);
        Collections.reverse(recent);
        SplitMessageContext context = SplitMessageContext.from(recent);
        return splitCandidateGate.isSplitCandidate(recent, context) ? context : null;
    }

    private AiModerationResult ruleResponse(ModerationResult result) {
        return new AiModerationResult(result, RULE_PROVIDER, RULE_MODEL, null, null, null);
    }

    private AnalysisResponse ruleAnalysis(ModerationResult result) {
        return new AnalysisResponse(ruleResponse(result), RULE_PROMPT_VERSION);
    }

    private static AnalysisResponse providerAnalysis(AiModerationResult response, String promptVersion) {
        return new AnalysisResponse(response, promptVersion);
    }

    /** #59가 Kafka Retry를 소진하고 DLT로 보낼 때만 호출하는 최종 실패 기록 진입점이다. */
    public void recordFinalFailure(Long messageId, String errorCode) {
        ChatModeration existing = moderations.findByMessageId(messageId).orElse(null);
        if (existing != null && existing.isCompleted()) {
            return;
        }
        persistFailure(messageId, existing, 0L, errorCode);
    }

    private void persistCompleted(
            Long messageId,
            ChatModeration existing,
            AiModerationResult response,
            String promptVersion,
            long latencyMillis) {
        Instant now = clock.instant();
        ChatModeration moderation = existing == null
                ? ChatModeration.completed(
                        messageId,
                        response.result().result(),
                        response.result().categories(),
                        response.result().riskLevel(),
                        response.provider(),
                        response.model(),
                        promptVersion,
                        ModerationPrompt.POLICY_VERSION,
                        latencyMillis,
                        response.promptTokens(),
                        response.completionTokens(),
                        response.totalTokens(),
                        now)
                : existing;
        if (existing != null) {
            moderation.complete(
                    response.result().result(),
                    response.result().categories(),
                    response.result().riskLevel(),
                    response.provider(),
                    response.model(),
                    promptVersion,
                    ModerationPrompt.POLICY_VERSION,
                    latencyMillis,
                    response.promptTokens(),
                    response.completionTokens(),
                    response.totalTokens(),
                    now);
        }
        try {
            moderations.saveAndFlush(moderation);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
            ChatModeration latest = moderations.findByMessageId(messageId).orElseThrow(() -> exception);
            if (latest.isCompleted()) {
                return;
            }
            latest.complete(
                    response.result().result(),
                    response.result().categories(),
                    response.result().riskLevel(),
                    response.provider(),
                    response.model(),
                    promptVersion,
                    ModerationPrompt.POLICY_VERSION,
                    latencyMillis,
                    response.promptTokens(),
                    response.completionTokens(),
                    response.totalTokens(),
                    now);
            try {
                moderations.saveAndFlush(latest);
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException retryException) {
                if (moderations.findByMessageId(messageId)
                        .filter(ChatModeration::isCompleted)
                        .isPresent()) {
                    return;
                }
                throw retryException;
            }
        }
        log.info(
                "event=CHAT_MODERATION_COMPLETED messageId={} result={} categories={} riskLevel={} latencyMillis={}",
                messageId,
                response.result().result(),
                response.result().categories(),
                response.result().riskLevel(),
                latencyMillis);
    }

    private void persistFailure(
            Long messageId,
            ChatModeration existing,
            long latencyMillis,
            String errorCode) {
        Instant now = clock.instant();
        ChatModeration moderation = existing == null
                ? ChatModeration.failed(
                        messageId,
                        "OpenAI",
                        "NOT_MEASURED",
                        ModerationPrompt.PROMPT_VERSION,
                        ModerationPrompt.POLICY_VERSION,
                        latencyMillis,
                        now,
                        errorCode)
                : existing;
        if (existing != null) {
            moderation.fail(
                    "OpenAI",
                    "NOT_MEASURED",
                    ModerationPrompt.PROMPT_VERSION,
                    ModerationPrompt.POLICY_VERSION,
                    latencyMillis,
                    now,
                    errorCode);
        }
        try {
            moderations.saveAndFlush(moderation);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
            if (moderations.findByMessageId(messageId).isPresent()) {
                return;
            }
            throw exception;
        }
        log.warn(
                "event=CHAT_MODERATION_FAILED messageId={} errorCode={} latencyMillis={}",
                messageId,
                errorCode,
                latencyMillis);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record AnalysisResponse(AiModerationResult response, String promptVersion) {
    }
}
