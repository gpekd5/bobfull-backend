package com.bobfull.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// DB 저장 시각과 애플리케이션 시간 계산에 사용할 UTC Clock을 제공한다.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
