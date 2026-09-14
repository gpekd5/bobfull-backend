package com.bobfull.chat.infrastructure.redis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

class RedisChatMessageListenerContainerTest {

    @Test
    void Redis_subscription_연결_실패를_메트릭으로_기록한다() {
        BusinessMetricRecorder metrics = mock(BusinessMetricRecorder.class);
        RedisChatMessageListenerContainer container = new RedisChatMessageListenerContainer(metrics);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(container, "handleSubscriptionException",
                new CompletableFuture<Void>(), new FixedBackOff(1, 0).start(), new IllegalStateException()))
                .isInstanceOf(IllegalStateException.class);

        verify(metrics).increment(BusinessMetricEvent.CHAT_REALTIME_SUBSCRIBE_FAILED);
    }
}
