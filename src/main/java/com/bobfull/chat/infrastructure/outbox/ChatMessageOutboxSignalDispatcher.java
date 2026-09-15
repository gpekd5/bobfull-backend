package com.bobfull.chat.infrastructure.outbox;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ChatMessage 저장 트랜잭션의 커밋 스레드가 Kafka ACK 대기로 막히지 않도록,
 * {@code ChatMessageOutboxProcessor.signal(...)} 호출을 별도 스레드로 넘긴다.
 * signal 디스패치 자체가 유실돼도 {@code ChatMessageOutboxScheduler}가 PENDING Outbox를
 * 폴링해 재처리하므로 정합성에는 영향이 없다(순수 응답성 최적화 경로) — 그래서 큐를
 * 무제한으로 두지 않고, Kafka 장기 장애 중 큐가 쌓여 메모리를 압박하지 않도록 유한 큐를
 * 쓰고 포화 시 그 signal만 안전하게 버린다(호출자에게 예외를 전파하지 않음).
 */
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
        return (runnable, executor) -> log.warn(
                "event=OUTBOX_SIGNAL_QUEUE_SATURATED reason=queue_capacity_{}_exceeded action=dropped_signal_scheduler_will_recover",
                QUEUE_CAPACITY);
    }
}
