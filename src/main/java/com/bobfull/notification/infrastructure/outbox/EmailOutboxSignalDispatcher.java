package com.bobfull.notification.infrastructure.outbox;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 커밋된 이메일 Outbox의 즉시 처리를 요청 스레드 밖으로 넘긴다.
 *
 * <p>제출 거부 시 Outbox를 claim하거나 완료 처리하지 않는다. 따라서 PENDING 이벤트는 기존 Scheduler가
 * 다음 polling에서 처리한다.</p>
 */
@Component
public class EmailOutboxSignalDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxSignalDispatcher.class);

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
            log.warn("event=EMAIL_OUTBOX_SIGNAL_REJECTED outboxEventId={} status=PENDING reason={}",
                    eventId, exception.getClass().getSimpleName());
        }
    }
}
