package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.infrastructure.metrics.ChatModerationMetrics;
import com.openai.errors.OpenAIException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

// Moderation 정책 Prompt와 Spring AI Structured Output 호출을 외부 Adapter에 격리한다.
@Component
@ConditionalOnProperty(prefix = "bobfull.ai.moderation", name = "fake-enabled", havingValue = "false", matchIfMissing = true)
public class SpringAiModerationAdapter implements AiModerationPort {
    private final ChatClient chatClient;
    private final String configuredModel;
    private final int maxOutputTokens;
    private final ChatModerationMetrics metrics;

    public SpringAiModerationAdapter(
            @Qualifier("moderationChatClient") ChatClient moderationChatClient,
            @Value("${spring.ai.openai.chat.model:gpt-4o-mini}") String configuredModel,
            @Value("${bobfull.ai.moderation.max-output-tokens:128}") int maxOutputTokens,
            ChatModerationMetrics metrics) {
        this.chatClient = moderationChatClient;
        this.configuredModel = configuredModel;
        this.maxOutputTokens = maxOutputTokens;
        this.metrics = metrics;
    }

    @Override
    public AiModerationResult analyze(String content) {

        ResponseEntity<ChatResponse, ModerationResult> response;

        try {
            response = chatClient.prompt()
                    .system(ModerationPrompt.SYSTEM_PROMPT)
                    .user(content)
                    .options(ModerationOpenAiOptions.withMaxOutputTokens(maxOutputTokens))
                    .call()
                    .responseEntity(ModerationResult.class, spec -> spec.useProviderStructuredOutput());
        } catch (JacksonException ex) {
            metrics.recordFailure("parse", ModerationPrompt.PROMPT_VERSION, ModerationPrompt.POLICY_VERSION);
            throw ex;
        } catch (OpenAIException ex) {
            metrics.recordFailure("provider", ModerationPrompt.PROMPT_VERSION, ModerationPrompt.POLICY_VERSION);
            throw ex;
        }


        ChatResponseMetadata metadata = response.response().getMetadata();

        Usage usage = metadata == null ? null : metadata.getUsage();
        String model = metadata == null || metadata.getModel() == null ? configuredModel : metadata.getModel();

        return new AiModerationResult(response.entity(), "OpenAI", model,
                usage == null ? null : asLong(usage.getPromptTokens()),
                usage == null ? null : asLong(usage.getCompletionTokens()),
                usage == null ? null : asLong(usage.getTotalTokens()));
    }
    private static Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
