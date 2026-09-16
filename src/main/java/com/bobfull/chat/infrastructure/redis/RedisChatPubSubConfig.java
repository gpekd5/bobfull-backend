package com.bobfull.chat.infrastructure.redis;

import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

// Redis 연결 복구 후 동일 채널을 다시 구독하는 listener container를 구성한다.
@Configuration
@ConditionalOnProperty(prefix = "chat.redis-pubsub", name = "subscriber-enabled", havingValue = "true", matchIfMissing = true)
public class RedisChatPubSubConfig {
    @Bean
    public RedisMessageListenerContainer redisChatMessageListenerContainer(RedisConnectionFactory connectionFactory,
            RedisChatMessageSubscriber subscriber, BusinessMetricRecorder businessMetricRecorder,
            @Value("${chat.redis-pubsub.channel:bobfull:chat:messages}") String channel,
            @Value("${chat.redis-pubsub.reconnect-delay:5s}") Duration reconnectDelay) {
        RedisMessageListenerContainer container = new RedisChatMessageListenerContainer(businessMetricRecorder);
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(reconnectDelay.toMillis());
        container.addMessageListener(subscriber, new ChannelTopic(channel));
        return container;
    }
}
