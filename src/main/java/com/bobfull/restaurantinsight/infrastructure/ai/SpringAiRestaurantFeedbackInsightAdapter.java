package com.bobfull.restaurantinsight.infrastructure.ai;

import com.bobfull.restaurantinsight.application.dto.RestaurantFeedbackAnalysis;
import com.bobfull.restaurantinsight.application.port.RestaurantFeedbackInsightPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bobfull.ai.restaurant-insight", name = "enabled", havingValue = "true")
public class SpringAiRestaurantFeedbackInsightAdapter implements RestaurantFeedbackInsightPort {
    private final ChatClient chatClient;
    public SpringAiRestaurantFeedbackInsightAdapter(@Qualifier("restaurantInsightChatClient") ChatClient restaurantInsightChatClient) { this.chatClient = restaurantInsightChatClient; }
    @Override public Result analyze(String content) {
        ResponseEntity<ChatResponse, RestaurantFeedbackAnalysis> response = chatClient.prompt().system(RestaurantFeedbackPrompt.SYSTEM_PROMPT).user(content).call().responseEntity(RestaurantFeedbackAnalysis.class, spec -> spec.useProviderStructuredOutput());
        ChatResponseMetadata metadata = response.response().getMetadata();
        String model = metadata == null || metadata.getModel() == null ? "unknown" : metadata.getModel();
        return new Result(response.entity(), "OpenAI", model);
    }
}
