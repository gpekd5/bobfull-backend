package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;

/**
 * Kafka Retry가 모두 소진된 레코드를 DLT 토픽으로 옮기고, #66의 최종 실패 기록 진입점을 호출한다.
 * DLT 이동과 recordFinalFailure 호출 순서를 이 클래스 하나에서 보장한다.
 */
@Slf4j
@Component
public class ChatModerationDltRecoverer implements ConsumerRecordRecoverer {
    private final DeadLetterPublishingRecoverer delegate;
    private final ChatModerationService chatModerationService;
    private final BusinessMetricRecorder businessMetricRecorder;

    public ChatModerationDltRecoverer(KafkaOperations<Object, Object> kafkaTemplate,
            ChatModerationService chatModerationService, BusinessMetricRecorder businessMetricRecorder,
            @Value("${bobfull.kafka.chat-message.dlt-topic:bobfull.chat.message-created.dlt.v1}") String dltTopic) {
        this.delegate = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(dltTopic, record.partition()));
        // DLT 발행 자체가 실패하면 recordFinalFailure를 호출하지 않아야 하므로,
        // Spring Kafka 버전 기본값에 암묵적으로 의존하지 않고 명시적으로 강제한다.
        this.delegate.setFailIfSendResultIsError(true);
        this.chatModerationService = chatModerationService;
        this.businessMetricRecorder = businessMetricRecorder;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        delegate.accept(record, exception); // DLT 발행 실패 시 예외를 던져 아래 recordFinalFailure를 막는다
        String errorCode = ListenerExceptionUnwrapper.errorCodeOf(exception);
        Long messageId = messageIdOf(record);
        if (messageId != null) {
            chatModerationService.recordFinalFailure(messageId, errorCode);
        } else {
            log.error("event=CHAT_MODERATION_DLT_MESSAGE_ID_MISSING topic={} partition={} offset={} errorCode={}",
                    record.topic(), record.partition(), record.offset(), errorCode);
        }
        businessMetricRecorder.increment(BusinessMetricEvent.CHAT_MODERATION_RETRY_EXHAUSTED);
        log.error("event=CHAT_MODERATION_RETRY_EXHAUSTED topic={} partition={} offset={} messageId={} errorCode={}",
                record.topic(), record.partition(), record.offset(), messageId, errorCode);
    }

    private Long messageIdOf(ConsumerRecord<?, ?> record) {
        if (record.value() instanceof ChatMessageCreatedEvent event) {
            return event.messageId();
        }
        return null;
    }
}
