package com.bobfull.restaurantinsight.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.infrastructure.kafka.InvalidChatMessageEventException;
import com.bobfull.restaurantinsight.application.service.RestaurantFeedbackInsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 채팅 생성 이벤트를 검증하고 Restaurant Insight 분석 흐름을 시작한다.
@Component
@ConditionalOnProperty(prefix = "bobfull.kafka.restaurant-insight", name = "consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class RestaurantFeedbackInsightConsumer {

    private final RestaurantFeedbackInsightService service;

    // 지원하는 이벤트 버전만 분석 Service에 전달해 retry·DLT 경계를 형성한다.
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
