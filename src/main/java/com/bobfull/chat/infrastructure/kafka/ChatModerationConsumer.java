package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.service.ChatModerationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * #66 ChatModerationService.analyze(messageId)만 호출한다. AiModerationPort/ChatClient/OpenAI를
 * 직접 다루지 않으며, 실패는 그대로 던져 컨테이너의 CommonErrorHandler(Retry/DLT)가 처리하게 한다.
 */
@Component
@ConditionalOnProperty(prefix = "bobfull.kafka.chat-message", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
public class ChatModerationConsumer {

    private final ChatModerationService chatModerationService;

    public ChatModerationConsumer(ChatModerationService chatModerationService) {
        this.chatModerationService = chatModerationService;
    }

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
