package com.bobfull.chat.infrastructure.redis;

import com.bobfull.chat.presentation.dto.ChatMessageSentResponse;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 커밋된 채팅 메시지만 Redis Pub/Sub 채널로 best-effort 전파한다. */
@Component
public class RedisChatMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(RedisChatMessagePublisher.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessMetricRecorder businessMetricRecorder;
    private final String channel;

    public RedisChatMessagePublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            BusinessMetricRecorder businessMetricRecorder,
            @Value("${chat.redis-pubsub.channel:bobfull:chat:messages}") String channel) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.businessMetricRecorder = businessMetricRecorder;
        this.channel = channel;
    }

    public void publish(ChatMessageSentResponse response) {
        try {
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(ChatRealtimeMessage.from(response)));
            log.info("CHAT_REALTIME_PUBLISHED messageId={} chatRoomId={}",
                    response.messageId(), response.chatRoomId());
        } catch (RuntimeException exception) {
            log.error("event=CHAT_REALTIME_PUBLISH_FAILED messageId={} chatRoomId={} reason={}",
                    response.messageId(), response.chatRoomId(), exception.getClass().getSimpleName());
            businessMetricRecorder.increment(BusinessMetricEvent.CHAT_REALTIME_PUBLISH_FAILED);
        }
    }
}
