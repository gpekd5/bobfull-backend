package com.bobfull.chat.infrastructure.outbox;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 커밋 스레드가 Kafka ACK를 기다리지 않도록 Outbox 즉시 처리를 별도 스레드에 제출한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageOutboxSignalDispatcher {

    private static final int QUEUE_CAPACITY = 100;

    private final ChatMessageOutboxProcessor processor;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "chat-message-outbox-signal");
                thread.setDaemon(true);
                return thread;
            },
            discardAndLog());

    public void dispatch(Long outboxEventId) {
        executor.execute(() -> {
            try {
                processor.signal(outboxEventId);
            } catch (RuntimeException exception) {
                log.error("event=OUTBOX_SIGNAL_DISPATCH_FAILED outboxEventId={} reason={}",
                        outboxEventId, exception.toString(), exception);
            }
        });
    }

    private static RejectedExecutionHandler discardAndLog() {
        // 포화 시 signal만 버리고 PENDING 이벤트는 Scheduler polling이 복구하도록 남긴다.
        return (runnable, executor) -> log.warn(
                "event=OUTBOX_SIGNAL_QUEUE_SATURATED reason=queue_capacity_{}_exceeded action=dropped_signal_scheduler_will_recover",
                QUEUE_CAPACITY);
    }
}
