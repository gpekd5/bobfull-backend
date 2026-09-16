package com.bobfull.chat.infrastructure.redis;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// Redis에서 공유된 메시지를 현재 App instance의 STOMP 구독자에게 전달한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMessageSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final BusinessMetricRecorder businessMetricRecorder;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ChatRealtimeMessage payload = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), ChatRealtimeMessage.class);
            messagingTemplate.convertAndSend("/sub/chat/rooms/" + payload.chatRoomId(), payload);
            log.info("CHAT_REALTIME_SUBSCRIBED messageId={} chatRoomId={}",
                    payload.messageId(), payload.chatRoomId());
        } catch (RuntimeException exception) {
            log.error("event=CHAT_REALTIME_SUBSCRIBE_FAILED reason={}", exception.getClass().getSimpleName());
            businessMetricRecorder.increment(BusinessMetricEvent.CHAT_REALTIME_SUBSCRIBE_FAILED);
        }
    }
}
