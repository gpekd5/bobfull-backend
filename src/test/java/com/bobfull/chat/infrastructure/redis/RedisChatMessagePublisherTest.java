package com.bobfull.chat.infrastructure.redis;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bobfull.chat.presentation.response.ChatMessageSentResponse;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class RedisChatMessagePublisherTest {
    @Test void 저장된_메시지를_고정_채널에_JSON으로_한번_발행한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisChatMessagePublisher publisher = new RedisChatMessagePublisher(redisTemplate, JsonMapper.builder().findAndAddModules().build(), mock(BusinessMetricRecorder.class), "bobfull:chat:messages");

        publisher.publish(response());

        verify(redisTemplate).convertAndSend(eq("bobfull:chat:messages"), contains("\"messageId\":11"));
    }

    @Test void Redis_발행_실패는_저장_완료_경로에_예외를_전파하지_않고_메트릭을_남긴다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        BusinessMetricRecorder metrics = mock(BusinessMetricRecorder.class);
        doThrow(new IllegalStateException()).when(redisTemplate).convertAndSend(anyString(), anyString());
        RedisChatMessagePublisher publisher = new RedisChatMessagePublisher(redisTemplate, JsonMapper.builder().findAndAddModules().build(), metrics, "bobfull:chat:messages");

        publisher.publish(response());

        verify(metrics).increment(BusinessMetricEvent.CHAT_REALTIME_PUBLISH_FAILED);
    }

    private ChatMessageSentResponse response() { return new ChatMessageSentResponse(11L, 3L, 7L, 8L, "회원", "안녕", Instant.parse("2026-08-12T00:00:00Z")); }
}
