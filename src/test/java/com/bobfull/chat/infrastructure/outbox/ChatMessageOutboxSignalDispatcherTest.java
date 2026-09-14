package com.bobfull.chat.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 리뷰 지적(MAJOR 1) 재검증: Kafka ACK 대기가 느려도 dispatch(...) 호출 자체(=채팅 send 경로)는
 * 즉시 반환돼야 한다. processor.signal(...)의 실제 지연은 별도 스레드에서만 발생해야 한다.
 */
class ChatMessageOutboxSignalDispatcherTest {

    @Test void dispatch는_processor_signal이_느려도_즉시_반환한다() throws InterruptedException {
        // given
        ChatMessageOutboxProcessor processor = mock(ChatMessageOutboxProcessor.class);
        CountDownLatch signalStarted = new CountDownLatch(1);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            signalStarted.countDown();
            releaseSignal.await(5, TimeUnit.SECONDS);
            return null;
        }).when(processor).signal(1L);
        ChatMessageOutboxSignalDispatcher dispatcher = new ChatMessageOutboxSignalDispatcher(processor);

        // when
        long startedAt = System.nanoTime();
        dispatcher.dispatch(1L);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // then: dispatch 호출 자체는 즉시 반환(=채팅 send 경로가 Kafka ACK 대기로 막히지 않음)
        assertThat(elapsedMillis).isLessThan(500);
        assertThat(signalStarted.await(2, TimeUnit.SECONDS)).isTrue();
        releaseSignal.countDown();
        verify(processor, timeout(2000)).signal(1L);
    }

    @Test void processor_signal이_예외를_던져도_dispatch_호출자에게는_전파되지_않는다() {
        // given
        ChatMessageOutboxProcessor processor = mock(ChatMessageOutboxProcessor.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("강제 실패(테스트)")).when(processor).signal(2L);
        ChatMessageOutboxSignalDispatcher dispatcher = new ChatMessageOutboxSignalDispatcher(processor);

        // when / then
        org.assertj.core.api.Assertions.assertThatCode(() -> dispatcher.dispatch(2L)).doesNotThrowAnyException();
        verify(processor, timeout(2000)).signal(2L);
    }

    /**
     * 리뷰 지적(신규 MAJOR) 재검증: Kafka 장기 장애로 두 worker가 모두 ACK 대기로 막혀도,
     * 큐가 가득 찬 뒤의 dispatch(...)는 예외를 던지지 않고(=채팅 send 경로에 영향 없음) 즉시 반환돼야 한다.
     * 유한 큐이므로 처리되지 못한 signal은 안전하게 버려지고, PENDING Outbox는 Scheduler가 회수한다.
     */
    @Test void 큐가_가득_차도_dispatch는_예외없이_즉시_반환하고_worker를_막지_않는다() throws InterruptedException {
        // given: 두 worker를 모두 붙잡아 두어 큐가 실제로 쌓이게 만든다.
        ChatMessageOutboxProcessor processor = mock(ChatMessageOutboxProcessor.class);
        CountDownLatch bothWorkersBusy = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            bothWorkersBusy.countDown();
            releaseWorkers.await(5, TimeUnit.SECONDS);
            return null;
        }).when(processor).signal(0L);
        ChatMessageOutboxSignalDispatcher dispatcher = new ChatMessageOutboxSignalDispatcher(processor);
        dispatcher.dispatch(0L);
        dispatcher.dispatch(0L);
        assertThat(bothWorkersBusy.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            // when: 큐 용량(100)을 훨씬 넘겨 포화 상태를 강제한다.
            long startedAt = System.nanoTime();
            for (long id = 1; id <= 300; id++) {
                dispatcher.dispatch(id);
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            // then: 300회 모두 예외 없이, 그리고 블로킹 없이 빠르게 반환돼야 한다.
            assertThat(elapsedMillis).isLessThan(2000);
        } finally {
            releaseWorkers.countDown();
        }
    }
}
