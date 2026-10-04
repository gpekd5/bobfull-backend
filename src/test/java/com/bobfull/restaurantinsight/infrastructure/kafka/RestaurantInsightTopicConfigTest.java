package com.bobfull.restaurantinsight.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RestaurantInsightTopicConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RestaurantInsightTopicConfig.class);

    @Test
    void Insight_Consumer가_비활성이어도_DLT는_source와_같은_partition으로_구성된다() {
        contextRunner
                .withPropertyValues(
                        "bobfull.kafka.chat-message.topic-auto-create-enabled=true",
                        "bobfull.kafka.chat-message.partitions=3",
                        "bobfull.kafka.chat-message.replicas=1",
                        "bobfull.kafka.restaurant-insight.consumer-enabled=false",
                        "bobfull.kafka.restaurant-insight.dlt-topic=restaurant-insight-config-test.dlt.v1"
                )
                .run(context -> {
                    NewTopic topic = context.getBean("restaurantInsightDltTopic", NewTopic.class);

                    assertThat(topic.name()).isEqualTo("restaurant-insight-config-test.dlt.v1");
                    assertThat(topic.numPartitions()).isEqualTo(3);
                    assertThat(topic.replicationFactor()).isEqualTo((short) 1);
                });
    }
}
