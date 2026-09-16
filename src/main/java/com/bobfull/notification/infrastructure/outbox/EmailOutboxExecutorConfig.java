package com.bobfull.notification.infrastructure.outbox;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// SMTP I/O를 요청 처리와 분리하고 적체를 제한하는 이메일 Outbox executor를 구성한다.
@Configuration
public class EmailOutboxExecutorConfig {

    @Bean("emailOutboxExecutor")
    public ThreadPoolTaskExecutor emailOutboxExecutor(
            @Value("${outbox.email.executor.core-pool-size:2}") int corePoolSize,
            @Value("${outbox.email.executor.max-pool-size:2}") int maxPoolSize,
            @Value("${outbox.email.executor.queue-capacity:100}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("email-outbox-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
