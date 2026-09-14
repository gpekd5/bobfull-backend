package com.bobfull.notification.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EmailOutboxSignalDispatcherTest {

    @Test
    void 느린_SMTP_처리가_있어도_dispatch는_요청_스레드를_블로킹하지_않는다() throws InterruptedException {
        // given
        EmailOutboxProcessor processor = mock(EmailOutboxProcessor.class);
        CountDownLatch signalStarted = new CountDownLatch(1);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            signalStarted.countDown();
            releaseSignal.await(5, TimeUnit.SECONDS);
            return null;
        }).when(processor).signal(1L);
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = executor();
        EmailOutboxSignalDispatcher dispatcher = new EmailOutboxSignalDispatcher(executor, processor);

        try {
            // when
            long startedAt = System.nanoTime();
            dispatcher.dispatch(1L);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            // then
            assertThat(elapsedMillis).isLessThan(500);
            assertThat(signalStarted.await(2, TimeUnit.SECONDS)).isTrue();
            verify(processor, timeout(2000)).signal(1L);
        } finally {
            releaseSignal.countDown();
            executor.shutdown();
        }
    }

    @Test
    void Executor_제출이_거부되면_processor를_호출하지_않아_PENDING_이벤트를_Scheduler가_복구할수_있다() {
        // given
        EmailOutboxProcessor processor = mock(EmailOutboxProcessor.class);
        Executor rejectedExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        EmailOutboxSignalDispatcher dispatcher = new EmailOutboxSignalDispatcher(rejectedExecutor, processor);

        // when
        dispatcher.dispatch(1L);

        // then
        verifyNoInteractions(processor);
    }

    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("email-outbox-test-");
        executor.initialize();
        return executor;
    }
}
