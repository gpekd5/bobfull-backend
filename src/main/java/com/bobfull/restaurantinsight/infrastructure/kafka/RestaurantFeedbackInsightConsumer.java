package com.bobfull.restaurantinsight.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.infrastructure.kafka.InvalidChatMessageEventException;
import com.bobfull.restaurantinsight.application.service.RestaurantFeedbackInsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bobfull.kafka.restaurant-insight", name = "consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class RestaurantFeedbackInsightConsumer {

    private final RestaurantFeedbackInsightService service;

    @KafkaListener(
            topics = "${bobfull.kafka.chat-message.topic:bobfull.chat.message-created.v1}",
            groupId = "${bobfull.kafka.restaurant-insight.group-id:bobfull-restaurant-insight-staging}",
            containerFactory = "restaurantInsightKafkaListenerContainerFactory",
            concurrency = "${bobfull.kafka.restaurant-insight.consumer-concurrency:1}"
    )
    public void onChatMessageCreated(ChatMessageCreatedEvent event) {
        if (event.eventVersion() != 1) {
            throw new InvalidChatMessageEventException("Unsupported eventVersion=" + event.eventVersion());
        }
        service.analyze(event.messageId());
    }
}
