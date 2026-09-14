package com.bobfull.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.bobfull.chat.presentation.dto.ChatMessageSentResponse;
import com.bobfull.chat.infrastructure.redis.ChatRealtimeMessage;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Redis에 연결한 두 인스턴스 listener container가 서로 같은 realtime payload를 받는지 검증한다.
 * WebSocket/JWT/DB 저장은 이 테스트의 책임이 아니며 Phase B 운영 검증과 분리한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisChatCrossInstanceIntegrationTest {

    private static final String CHANNEL = "bobfull:chat:messages";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void 같은_Redis를_구독한_두_인스턴스는_메시지를_각자_한번씩_로컬_STOMP에_전달하고_다른_방과_섞지_않는다() throws Exception {
        // given
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        SimpMessagingTemplate instanceA = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate instanceB = mock(SimpMessagingTemplate.class);
        RedisMessageListenerContainer containerA = container(connectionFactory, instanceA);
        RedisMessageListenerContainer containerB = container(connectionFactory, instanceB);
        containerA.start();
        containerB.start();
        waitForSubscription();

        try {
            RedisChatMessagePublisher publisher = new RedisChatMessagePublisher(
                    new StringRedisTemplate(connectionFactory),
                    JsonMapper.builder().findAndAddModules().build(),
                    mock(BusinessMetricRecorder.class), CHANNEL
            );

            // when
            publisher.publish(message(101L, 10L, "A에서 보낸 메시지"));
            publisher.publish(message(102L, 11L, "다른 채팅방 메시지"));

            // then
            verify(instanceA, timeout(5_000).times(1)).convertAndSend(eq("/sub/chat/rooms/10"), any(ChatRealtimeMessage.class));
            verify(instanceB, timeout(5_000).times(1)).convertAndSend(eq("/sub/chat/rooms/10"), any(ChatRealtimeMessage.class));
            verify(instanceA, timeout(5_000).times(1)).convertAndSend(eq("/sub/chat/rooms/11"), any(ChatRealtimeMessage.class));
            verify(instanceB, timeout(5_000).times(1)).convertAndSend(eq("/sub/chat/rooms/11"), any(ChatRealtimeMessage.class));
        } finally {
            containerA.stop();
            containerB.stop();
            connectionFactory.destroy();
        }
    }

    private RedisMessageListenerContainer container(LettuceConnectionFactory connectionFactory,
            SimpMessagingTemplate messagingTemplate) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                new RedisChatMessageSubscriber(JsonMapper.builder().findAndAddModules().build(), messagingTemplate,
                        mock(BusinessMetricRecorder.class)),
                new ChannelTopic(CHANNEL)
        );
        container.afterPropertiesSet();
        return container;
    }

    private void waitForSubscription() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(250);
        assertThat(REDIS.isRunning()).isTrue();
    }

    private ChatMessageSentResponse message(Long messageId, Long chatRoomId, String content) {
        return new ChatMessageSentResponse(messageId, chatRoomId, 7L, 8L, "회원", content,
                Instant.parse("2026-08-12T00:00:00Z"));
    }
}
