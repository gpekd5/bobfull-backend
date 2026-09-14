package com.bobfull.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #192 "왜 Kafka인가" 비교의 핵심 신뢰성 차이를 실증한다. Kafka는 아직 소비되지 않은 이벤트를
 * 브로커(디스크)에 보존하므로 Consumer가 죽어도 재시작 후 이어서 처리할 수 있다
 * (이미 {@code ChatModerationConsumerIntegrationTest}, {@code ChatModerationDltPublishFailureIntegrationTest}에서
 * 실측). 반대로 이 Async Baseline은 순수 인메모리 스레드풀 큐이므로, 큐에 쌓인(아직 시작하지 않은)
 * 작업은 프로세스가 죽는 순간 그대로 유실된다 — 이 테스트는 그 유실을 직접 재현한다.
 */
class ChatMessageAsyncModerationDispatcherReliabilityTest {

    @Test
    void 실행_중인_작업만_시작되고_큐에_쌓인_작업은_강제_종료시_그대로_유실된다() throws InterruptedException {
        ChatModerationService chatModerationService = mock(ChatModerationService.class);
        CountDownLatch runningTaskStarted = new CountDownLatch(1);
        CountDownLatch holdRunningTask = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            runningTaskStarted.countDown();
            holdRunningTask.await();
            return null;
        }).when(chatModerationService).analyze(1L);

        // concurrency=1이라 messageId=1L 작업이 스레드를 점유하는 동안 2L, 3L은 큐에서 대기한다.
        ChatMessageAsyncModerationDispatcher dispatcher =
                new ChatMessageAsyncModerationDispatcher(chatModerationService, 1, 5);

        dispatcher.dispatch(1L);
        assertThat(runningTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatch(2L);
        dispatcher.dispatch(3L);
        assertThat(dispatcher.queuedTaskCount()).isEqualTo(2);

        // 프로세스 크래시를 흉내낸다: 실행 중인 작업은 인터럽트되고, 큐에 있던 작업은 재시도 없이 사라진다.
        ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(dispatcher, "executor");
        executor.shutdownNow();
        holdRunningTask.countDown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // messageId=1L만 시작됐고, 큐에 있던 2L·3L은 analyze()가 한 번도 호출되지 않은 채 유실됐다.
        verify(chatModerationService).analyze(1L);
        verifyNoMoreInteractions(chatModerationService);
    }
}
