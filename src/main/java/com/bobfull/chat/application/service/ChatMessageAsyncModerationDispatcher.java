package com.bobfull.chat.application.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * #192 "왜 Outbox+Kafka인가"를 실측으로 비교하기 위한 Baseline이다. Outbox/Kafka 없이
 * ChatMessage 커밋 직후 바로 스레드풀에 AI 분석을 제출한다. Kafka 처리 경계와 달리 이
 * Baseline은 재시도·DLT·브로커 적체가 없다 — 그 차이가 비교의 핵심이므로 일부러 단순하게
 * 유지하고, 큐가 포화되면 재시도 없이 그대로 버린다. {@code bobfull.chat.moderation.async-baseline-enabled=true}
 * 일 때만 활성화되며 기본값(false)에서는 Bean 자체가 생성되지 않아 운영 경로에 영향이 없다.
 */
@Component
@ConditionalOnProperty(prefix = "bobfull.chat.moderation", name = "async-baseline-enabled", havingValue = "true")
public class ChatMessageAsyncModerationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageAsyncModerationDispatcher.class);

    private final ChatModerationService chatModerationService;
    private final ThreadPoolExecutor executor;

    public ChatMessageAsyncModerationDispatcher(ChatModerationService chatModerationService,
            @Value("${bobfull.chat.moderation.async-baseline-concurrency:8}") int concurrency,
            @Value("${bobfull.chat.moderation.async-baseline-queue-capacity:100}") int queueCapacity) {
        this.chatModerationService = chatModerationService;
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "chat-message-async-moderation");
                    thread.setDaemon(true);
                    return thread;
                },
                discardAndLog());
    }

    public void dispatch(Long messageId) {
        executor.execute(() -> {
            try {
                chatModerationService.analyze(messageId);
            } catch (RuntimeException exception) {
                log.error("event=ASYNC_MODERATION_DISPATCH_FAILED messageId={} reason={}",
                        messageId, exception.toString(), exception);
            }
        });
    }

    /** 실측 비교용: 큐에 남아있는(아직 시작하지 않은) 작업 수다. */
    public int queuedTaskCount() {
        return executor.getQueue().size();
    }

    private static RejectedExecutionHandler discardAndLog() {
        return (runnable, exec) -> log.warn(
                "event=ASYNC_MODERATION_QUEUE_SATURATED reason=queue_capacity_exceeded action=dropped_task_no_retry_no_dlt");
    }
}
