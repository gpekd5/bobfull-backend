package com.bobfull.chat.infrastructure.redis;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;

/** Redis subscription 연결 실패를 재연결 흐름과 별개로 관측한다. */
final class RedisChatMessageListenerContainer extends RedisMessageListenerContainer {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMessageListenerContainer.class);

    private final BusinessMetricRecorder businessMetricRecorder;

    RedisChatMessageListenerContainer(BusinessMetricRecorder businessMetricRecorder) {
        this.businessMetricRecorder = businessMetricRecorder;
    }

    @Override
    protected void handleSubscriptionException(CompletableFuture<Void> future,
            BackOffExecution backOffExecution, Throwable cause) {
        log.error("event=CHAT_REALTIME_SUBSCRIBE_FAILED reason={}", cause.getClass().getSimpleName());
        businessMetricRecorder.increment(BusinessMetricEvent.CHAT_REALTIME_SUBSCRIBE_FAILED);
        super.handleSubscriptionException(future, backOffExecution, cause);
    }
}
