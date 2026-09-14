package com.bobfull.restaurantinsight.infrastructure.kafka;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

/** Insight 실패를 Moderation 상태와 분리해 Insight DLT에만 기록한다. */
@Component
public class RestaurantInsightDltRecoverer implements ConsumerRecordRecoverer {
    private final DeadLetterPublishingRecoverer delegate; private final BusinessMetricRecorder metrics;
    public RestaurantInsightDltRecoverer(KafkaOperations<Object, Object> template, BusinessMetricRecorder metrics, @Value("${bobfull.kafka.restaurant-insight.dlt-topic:bobfull.restaurant-insight.dlt.v1}") String topic) {
        delegate = new DeadLetterPublishingRecoverer(template, (record, exception) -> new TopicPartition(topic, record.partition()));
        delegate.setFailIfSendResultIsError(true); this.metrics=metrics;
    }
    @Override public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        delegate.accept(record, exception); // DLT 전송 실패는 throw되어 offset 성공 처리를 막는다.
        metrics.increment(BusinessMetricEvent.RESTAURANT_INSIGHT_RETRY_EXHAUSTED);
    }
}
