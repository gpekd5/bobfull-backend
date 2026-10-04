package com.bobfull.restaurantinsight.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// Restaurant Insight 최종 실패 레코드용 DLT를 source Topic과 같은 partition 수로 구성한다.
@Configuration
@ConditionalOnProperty(
        prefix = "bobfull.kafka.chat-message",
        name = "topic-auto-create-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RestaurantInsightTopicConfig {

    @Bean
    public NewTopic restaurantInsightDltTopic(
            @Value("${bobfull.kafka.restaurant-insight.dlt-topic:bobfull.restaurant-insight.dlt.v1}") String dltTopic,
            @Value("${bobfull.kafka.chat-message.partitions:3}") int partitions,
            @Value("${bobfull.kafka.chat-message.replicas:1}") short replicas
    ) {
        return TopicBuilder.name(dltTopic).partitions(partitions).replicas(replicas).build();
    }
}
