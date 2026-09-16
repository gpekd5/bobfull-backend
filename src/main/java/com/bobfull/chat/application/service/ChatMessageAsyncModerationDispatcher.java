package com.bobfull.chat.application.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Outbox·Kafka 경로와 비교하기 위해 재시도 없이 AI 분석을 제출하는 측정용 Baseline이다.
@Component
// 기본값은 비활성이며, 활성화하면 큐 포화 시 작업을 버리고 Retry·DLT를 제공하지 않는다.
@ConditionalOnProperty(prefix = "bobfull.chat.moderation", name = "async-baseline-enabled", havingValue = "true")
@Slf4j
public class ChatMessageAsyncModerationDispatcher {
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

    // 커밋 후 전달된 메시지를 bounded executor에 제출하고 분석 실패는 호출자와 격리한다.
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

    // 측정 시 아직 시작하지 않은 작업 수를 제공한다.
    public int queuedTaskCount() {
        return executor.getQueue().size();
    }

    private static RejectedExecutionHandler discardAndLog() {
        return (runnable, exec) -> log.warn(
                "event=ASYNC_MODERATION_QUEUE_SATURATED reason=queue_capacity_exceeded action=dropped_task_no_retry_no_dlt");
    }
}
