package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.service.ChatModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 채팅 메시지 생성 이벤트를 AI 분석에 전달하고 실패를 Kafka Retry·DLT 경계로 전파한다.
@Component
@ConditionalOnProperty(prefix = "bobfull.kafka.chat-message", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ChatModerationConsumer {

    private final ChatModerationService chatModerationService;

    // 재전달될 수 있는 이벤트를 멱등 분석 서비스에 넘기고 계약 위반은 즉시 실패시킨다.
    @KafkaListener(
            topics = "${bobfull.kafka.chat-message.topic:bobfull.chat.message-created.v1}",
            groupId = "${spring.kafka.consumer.group-id:bobfull-chat-moderation}",
            containerFactory = "chatModerationKafkaListenerContainerFactory",
            concurrency = "${bobfull.kafka.chat-message.consumer-concurrency:1}"
    )
    public void onChatMessageCreated(ChatMessageCreatedEvent event) {
        if (event.eventVersion() != 1) {
            throw new InvalidChatMessageEventException(
                    "지원하지 않는 eventVersion입니다: " + event.eventVersion() + " messageId=" + event.messageId());
        }
        chatModerationService.analyze(event.messageId());
    }
}
