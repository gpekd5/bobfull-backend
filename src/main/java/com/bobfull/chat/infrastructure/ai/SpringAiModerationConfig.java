package com.bobfull.chat.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAI 전용 객체는 Adapter에만 주입되도록 ChatClient를 구성한다. */
@Configuration
public class SpringAiModerationConfig {
    @Bean("moderationChatClient")
    ChatClient moderationChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
