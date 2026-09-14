package com.bobfull.chat.infrastructure.redis;

import com.bobfull.chat.infrastructure.redis.ChatRealtimeMessage;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis에서 받은 메시지를 현재 인스턴스의 STOMP 세션에만 fan-out한다. */
@Component
public class RedisChatMessageSubscriber implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(RedisChatMessageSubscriber.class);
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final BusinessMetricRecorder businessMetricRecorder;

    public RedisChatMessageSubscriber(ObjectMapper objectMapper, SimpMessagingTemplate messagingTemplate,
            BusinessMetricRecorder businessMetricRecorder) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.businessMetricRecorder = businessMetricRecorder;
    }

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
