package com.bobfull.kafka.config;

import com.bobfull.common.exception.CustomException;
import com.bobfull.kafka.consumer.RestaurantInsightDltRecoverer;
import com.bobfull.chat.infrastructure.kafka.InvalidChatMessageEventException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class RestaurantInsightConsumerConfig {
    @Bean
    CommonErrorHandler restaurantInsightErrorHandler(RestaurantInsightDltRecoverer recoverer, @Value("${bobfull.kafka.restaurant-insight.consumer-max-attempts:3}") int attempts, @Value("${bobfull.kafka.restaurant-insight.consumer-retry-backoff-ms:1000}") long backoff) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(backoff, Math.max(0, attempts - 1)));
        handler.addNotRetryableExceptions(CustomException.class, InvalidChatMessageEventException.class); return handler;
    }
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> restaurantInsightKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            @Qualifier("restaurantInsightErrorHandler") CommonErrorHandler errorHandler) {
        // configurer.configure()가 spring.kafka.listener.*(ack-mode 등) 공통 설정을 먼저 적용한 뒤,
        // Insight 전용 ErrorHandler만 override한다.
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
