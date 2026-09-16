package com.bobfull.notification.infrastructure.outbox;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

// 커밋된 이메일 Outbox의 즉시 처리를 요청 스레드 밖으로 넘긴다.
@Slf4j
@Component
public class EmailOutboxSignalDispatcher {
    private final Executor executor;
    private final EmailOutboxProcessor processor;

    public EmailOutboxSignalDispatcher(@Qualifier("emailOutboxExecutor") Executor executor,
                                       EmailOutboxProcessor processor) {
        this.executor = executor;
        this.processor = processor;
    }

    public void dispatch(Long eventId) {
        try {
            executor.execute(() -> processor.signal(eventId));
        } catch (RejectedExecutionException exception) {
            // 제출 전에 실패했으므로 PENDING 상태를 유지하고 Scheduler의 다음 polling에 맡긴다.
            log.warn("event=EMAIL_OUTBOX_SIGNAL_REJECTED outboxEventId={} status=PENDING reason={}",
                    eventId, exception.getClass().getSimpleName());
        }
    }
}
