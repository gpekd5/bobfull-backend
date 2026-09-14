package com.bobfull.chat.infrastructure.redis;

import static org.mockito.Mockito.*;

import com.bobfull.chat.infrastructure.redis.ChatRealtimeMessage;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

class RedisChatMessageSubscriberTest {
    @Test void Redis_메시지를_현재_인스턴스의_채팅방_destination으로_한번_전달한다() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RedisChatMessageSubscriber subscriber = new RedisChatMessageSubscriber(JsonMapper.builder().findAndAddModules().build(), template, mock(BusinessMetricRecorder.class));

        subscriber.onMessage(new DefaultMessage("bobfull:chat:messages".getBytes(StandardCharsets.UTF_8), "{\"messageId\":11,\"chatRoomId\":3,\"senderMemberId\":7,\"senderParticipantId\":8,\"senderName\":\"회원\",\"content\":\"안녕\",\"sentAt\":\"2026-08-12T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8)), null);

        verify(template).convertAndSend(eq("/sub/chat/rooms/3"), any(ChatRealtimeMessage.class));
    }

    @Test void 잘못된_Redis_payload는_DB에_재저장하거나_예외를_전파하지_않고_메트릭을_남긴다() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        BusinessMetricRecorder metrics = mock(BusinessMetricRecorder.class);
        RedisChatMessageSubscriber subscriber = new RedisChatMessageSubscriber(JsonMapper.builder().findAndAddModules().build(), template, metrics);

        subscriber.onMessage(new DefaultMessage("channel".getBytes(StandardCharsets.UTF_8), "not-json".getBytes(StandardCharsets.UTF_8)), null);

        verifyNoInteractions(template);
        verify(metrics).increment(BusinessMetricEvent.CHAT_REALTIME_SUBSCRIBE_FAILED);
    }
}
