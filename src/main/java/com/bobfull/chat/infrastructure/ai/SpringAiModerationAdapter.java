package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.application.port.AiModerationPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** BobFull 정책 Prompt와 Spring AI Structured Output 호출을 격리하는 OpenAI Adapter다. */
@Component
@ConditionalOnProperty(prefix = "bobfull.ai.moderation", name = "fake-enabled", havingValue = "false", matchIfMissing = true)
public class SpringAiModerationAdapter implements AiModerationPort {
    private final ChatClient chatClient;
    private final String configuredModel;
    private final int maxOutputTokens;
    public SpringAiModerationAdapter(@Qualifier("moderationChatClient") ChatClient moderationChatClient,
            @Value("${spring.ai.openai.chat.model:gpt-4o-mini}") String configuredModel,
            @Value("${bobfull.ai.moderation.max-output-tokens:128}") int maxOutputTokens) {
        this.chatClient = moderationChatClient;
        this.configuredModel = configuredModel;
        this.maxOutputTokens = maxOutputTokens;
    }
    @Override
    public AiModerationResult analyze(String content) {
        ResponseEntity<ChatResponse, ModerationResult> response = chatClient.prompt()
                .system(ModerationPrompt.SYSTEM_PROMPT)
                .user(content)
                .options(ModerationOpenAiOptions.withMaxOutputTokens(maxOutputTokens))
                .call()
                .responseEntity(ModerationResult.class, spec -> spec.useProviderStructuredOutput());
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
