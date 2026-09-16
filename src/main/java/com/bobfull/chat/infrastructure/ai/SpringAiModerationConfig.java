package com.bobfull.chat.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Moderation 전용 ChatClient를 외부 AI Adapter에만 주입하도록 구성한다.
@Configuration
public class SpringAiModerationConfig {
    @Bean("moderationChatClient")
    ChatClient moderationChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
