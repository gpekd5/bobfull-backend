package com.bobfull.restaurantinsight.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "bobfull.ai.restaurant-insight", name = "enabled", havingValue = "true")
public class RestaurantFeedbackInsightAiConfig {

    @Bean("restaurantInsightChatClient")
    ChatClient restaurantInsightChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
